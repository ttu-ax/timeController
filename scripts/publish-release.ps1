[CmdletBinding()]
param(
    [string]$VersionName,
    [long]$VersionCode,
    [string[]]$ReleaseNotes,
    [string]$ReleaseNotesFile,
    [long]$MinimumSupportedVersionCode = 1,
    [switch]$ForceUpdate,
    [string]$ReleaseSiteDirectory,
    [string]$PublishOrigin = 'https://acupofttu.top',
    [switch]$PrepareOnly,
    [switch]$SkipVerify,
    [switch]$SkipGitChecks,
    [switch]$Push
)

# NOTE: This file must stay pure ASCII. Windows PowerShell 5.1 reads BOM-less
# scripts with the system ANSI code page, so non-ASCII literals would corrupt
# parsing on a Chinese Windows install.

$ErrorActionPreference = 'Stop'

$appId = 'time-controller'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$gradlePath = Join-Path $repoRoot 'app\build.gradle.kts'
$updateCheckerPath = Join-Path $repoRoot 'app\src\main\java\com\example\procrastinationgate\UpdateChecker.kt'
$outputApkRelative = 'app\build\outputs\apk\release\app-release.apk'
$siteOrigin = $PublishOrigin.TrimEnd('/')
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Stop-Release([string]$Message) {
    throw "RELEASE ABORTED: $Message"
}

function Read-Utf8([string]$Path) {
    return $utf8NoBom.GetString([System.IO.File]::ReadAllBytes($Path))
}

function Write-Utf8([string]$Path, [string]$Content) {
    [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}

function Invoke-Git([string[]]$Arguments) {
    # git writes harmless warnings (e.g. "LF will be replaced by CRLF") to
    # stderr. Redirecting them with $ErrorActionPreference = 'Stop' would turn
    # them into terminating errors, so relax it around the call and trust the
    # exit code instead.
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & git -C $repoRoot @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exitCode -ne 0) {
        throw "git $($Arguments -join ' ') failed (exit $exitCode): $($output -join ' ')"
    }
    return @($output | Where-Object { $_ -is [string] })
}

# ---------------------------------------------------------------------------
# 1. Locate the release site repository.
# ---------------------------------------------------------------------------
if (-not $ReleaseSiteDirectory) { $ReleaseSiteDirectory = $env:TIME_CONTROLLER_RELEASE_SITE }
if (-not $ReleaseSiteDirectory) {
    $ReleaseSiteDirectory = Join-Path (Split-Path $repoRoot -Parent) 'app-release-site'
}
if (-not (Test-Path -LiteralPath $ReleaseSiteDirectory -PathType Container)) {
    Stop-Release "release site directory not found: $ReleaseSiteDirectory. Pass -ReleaseSiteDirectory or set TIME_CONTROLLER_RELEASE_SITE."
}
$ReleaseSiteDirectory = (Resolve-Path -LiteralPath $ReleaseSiteDirectory).Path
$distRoot = Join-Path $ReleaseSiteDirectory 'dist'
$deployScript = Join-Path $ReleaseSiteDirectory 'scripts\deploy-site.ps1'
$registryPath = Join-Path $ReleaseSiteDirectory 'scripts\app-registry.json'
foreach ($required in @($deployScript, $registryPath)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        Stop-Release "missing site script or registry: $required"
    }
}

# ---------------------------------------------------------------------------
# 2. Read the app registry and check it still matches this project.
# ---------------------------------------------------------------------------
$registry = Read-Utf8 $registryPath | ConvertFrom-Json
if (-not $registry.apps -or -not ($registry.apps.PSObject.Properties.Name -contains $appId)) {
    Stop-Release "app-registry.json has no '$appId' entry."
}
$appConfig = $registry.apps.$appId

$gradleText = Read-Utf8 $gradlePath
$mName = [regex]::Match($gradleText, '(?m)^\s*versionName\s*=\s*"([^"]+)"\s*$')
$mCode = [regex]::Match($gradleText, '(?m)^\s*versionCode\s*=\s*(\d+)\s*$')
$mAppId = [regex]::Match($gradleText, '(?m)^\s*applicationId\s*=\s*"([^"]+)"\s*$')
if (-not $mName.Success -or -not $mCode.Success -or -not $mAppId.Success) {
    Stop-Release 'could not read versionName / versionCode / applicationId from app/build.gradle.kts.'
}
$localName = $mName.Groups[1].Value
$localCode = [long]$mCode.Groups[1].Value
$applicationId = $mAppId.Groups[1].Value

# 2a. The registry must point at this checkout.
$registryProject = [System.IO.Path]::GetFullPath($appConfig.projectDir).TrimEnd('\', '/').ToLowerInvariant()
$currentProject = [System.IO.Path]::GetFullPath($repoRoot).TrimEnd('\', '/').ToLowerInvariant()
if ($registryProject -ne $currentProject) {
    Stop-Release "app-registry.json projectDir is $registryProject but this repository is $currentProject."
}

# 2b. The registry package name must match the built applicationId.
if ($appConfig.packageName -ne $applicationId) {
    Stop-Release ("app-registry.json packageName is '$($appConfig.packageName)' but " +
        "app/build.gradle.kts applicationId is '$applicationId'. Update the registry first, " +
        'otherwise the published update.json carries the wrong package name.')
}

# 2c. The registry manifest path must match the URL the client polls.
$expectedManifestSubpath = "apps/$appId/update.json"
if ($appConfig.manifestSubpath -ne $expectedManifestSubpath) {
    Stop-Release "app-registry.json manifestSubpath is '$($appConfig.manifestSubpath)', expected '$expectedManifestSubpath'."
}
if (-not (Test-Path -LiteralPath $updateCheckerPath -PathType Leaf)) {
    Stop-Release "UpdateChecker.kt not found: $updateCheckerPath"
}
$updateUrlMatch = [regex]::Match((Read-Utf8 $updateCheckerPath), 'UPDATE_URL\s*=\s*"([^"]+)"')
if (-not $updateUrlMatch.Success) {
    Stop-Release 'could not read the UPDATE_URL constant from UpdateChecker.kt.'
}
$clientUpdateUrl = $updateUrlMatch.Groups[1].Value
$expectedUpdateUrl = "$siteOrigin/$expectedManifestSubpath"
if ($clientUpdateUrl -ne $expectedUpdateUrl) {
    Stop-Release ("the client polls $clientUpdateUrl but this release publishes to $expectedUpdateUrl, " +
        'so the app would never see the update.')
}

# ---------------------------------------------------------------------------
# 3. Git preconditions.
# ---------------------------------------------------------------------------
if (-not $SkipGitChecks) {
    $branch = (Invoke-Git @('branch', '--show-current') | Select-Object -First 1)
    if ($branch -ne 'main') { Stop-Release "release from the main branch (current: $branch)." }
    $staged = @(Invoke-Git @('diff', '--cached', '--name-only'))
    if ($staged.Count) { Stop-Release "staged but uncommitted changes present: $($staged -join ', ')" }
}

# ---------------------------------------------------------------------------
# 4. Decide the version to publish.
# ---------------------------------------------------------------------------
$manifestPath = Join-Path $distRoot "apps\$appId\update.json"
$publishedCode = 0
if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
    $publishedManifest = Read-Utf8 $manifestPath | ConvertFrom-Json
    if ($publishedManifest.version_code) { $publishedCode = [long]$publishedManifest.version_code }
}

$targetName = $VersionName
$targetCode = $VersionCode

if (-not $targetCode) {
    if ($localCode -gt $publishedCode) {
        # Already bumped locally (or a previous publish never reached upload):
        # publish the current version instead of stacking another bump.
        $targetCode = $localCode
        if (-not $targetName) { $targetName = $localName }
        Write-Host "Local version v$localName ($localCode) is ahead of the published $publishedCode; publishing it as-is."
    } else {
        $targetCode = $localCode + 1
    }
}
if (-not $targetName) {
    $semver = [regex]::Match($localName, '^(\d+)\.(\d+)\.(\d+)$')
    if (-not $semver.Success) { Stop-Release "current version '$localName' is not three-part; pass -VersionName." }
    $targetName = '{0}.{1}.{2}' -f $semver.Groups[1].Value, $semver.Groups[2].Value, ([long]$semver.Groups[3].Value + 1)
}
if ($targetName -notmatch '^[0-9A-Za-z][0-9A-Za-z._-]*$') {
    Stop-Release "version name '$targetName' contains invalid characters."
}
if ($targetCode -le $publishedCode) {
    Stop-Release "versionCode $targetCode must be greater than the published $publishedCode."
}
if ($targetCode -lt $localCode) {
    Stop-Release "versionCode $targetCode is lower than the $localCode in app/build.gradle.kts."
}
if ($MinimumSupportedVersionCode -lt 1 -or $MinimumSupportedVersionCode -gt $targetCode) {
    Stop-Release 'MinimumSupportedVersionCode must be between 1 and the new versionCode.'
}

# ---------------------------------------------------------------------------
# 5. Release notes.
#    -File invocation (what the .cmd wrapper uses) cannot bind an array, so
#    "-ReleaseNotes a,b" collapses into one literal string. Prefer
#    -ReleaseNotesFile or the interactive prompt.
# ---------------------------------------------------------------------------
if ($ReleaseNotesFile) {
    # Resolve relative to the repository root so the path does not depend on
    # where the .cmd happened to be launched from.
    $resolvedNotes = $ReleaseNotesFile
    if (-not [System.IO.Path]::IsPathRooted($resolvedNotes)) {
        $resolvedNotes = Join-Path $repoRoot $resolvedNotes
    }
    $resolvedNotes = [System.IO.Path]::GetFullPath($resolvedNotes)
    if (-not (Test-Path -LiteralPath $resolvedNotes -PathType Leaf)) {
        Stop-Release ("release notes file not found: $resolvedNotes. " +
            'Relative paths resolve against the repository root. ' +
            'Omit -ReleaseNotesFile to type the notes interactively.')
    }
    if ($ReleaseNotes -and $ReleaseNotes.Count) {
        Stop-Release 'pass either -ReleaseNotes or -ReleaseNotesFile, not both.'
    }
    $notesText = Read-Utf8 $resolvedNotes
    $ReleaseNotes = @($notesText -split "`r?`n" |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { $_.Trim() })
}
if (-not $ReleaseNotes -or -not $ReleaseNotes.Count) {
    $ReleaseNotes = @()
    Write-Host 'Enter release notes, one per line (blank line to finish):'
    while ($true) {
        $note = Read-Host '  >'
        if ([string]::IsNullOrWhiteSpace($note)) { break }
        $ReleaseNotes += $note.Trim()
    }
}
$ReleaseNotes = @($ReleaseNotes | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if (-not $ReleaseNotes.Count) { Stop-Release 'at least one release note is required.' }

$fileName = "$($appConfig.apkPrefix)-v$targetName.apk"
$downloadDir = Join-Path $distRoot 'downloads'
$apkDestination = Join-Path $downloadDir $fileName

Write-Host ''
Write-Host "=== $($appConfig.name) v$targetName (versionCode $targetCode) ==="
Write-Host "APK:          $fileName"
Write-Host "Release notes: $($ReleaseNotes -join ' | ')"
Write-Host ''

# ---------------------------------------------------------------------------
# 6. Write the new version and build.
# ---------------------------------------------------------------------------
$needsGradleWrite = ($targetCode -ne $localCode) -or ($targetName -ne $localName)
$gradleUpdated = [regex]::Replace($gradleText, '(?m)^([ \t]*versionCode[ \t]*=[ \t]*)\d+', { param($m) $m.Groups[1].Value + $targetCode }, 1)
$gradleUpdated = [regex]::Replace($gradleUpdated, '(?m)^([ \t]*versionName[ \t]*=[ \t]*)"[^"]+"', { param($m) $m.Groups[1].Value + '"' + $targetName + '"' }, 1)
if ($gradleUpdated -eq $gradleText -and $needsGradleWrite) {
    Stop-Release 'could not write the new version into app/build.gradle.kts.'
}

if ($needsGradleWrite) {
    Write-Utf8 $gradlePath $gradleUpdated
    Write-Host "Wrote version v$targetName ($targetCode) into app/build.gradle.kts."
} else {
    Write-Host "app/build.gradle.kts already at v$targetName ($targetCode); version untouched."
}

$apkSource = Join-Path $repoRoot $outputApkRelative
try {
    Write-Host "Building ($($appConfig.assembleTask))..."
    Push-Location $repoRoot
    try {
        & (Join-Path $repoRoot 'gradlew.bat') $appConfig.assembleTask '--no-daemon'
        if ($LASTEXITCODE -ne 0) { throw "gradle build failed (exit $LASTEXITCODE)." }
    } finally {
        Pop-Location
    }
    if (-not (Test-Path -LiteralPath $apkSource -PathType Leaf)) {
        throw "gradle did not produce $apkSource"
    }
} catch {
    if ($needsGradleWrite) { Write-Utf8 $gradlePath $gradleText }
    throw
}

# ---------------------------------------------------------------------------
# 7. Verify the APK signature.
# ---------------------------------------------------------------------------
function Find-AndroidSdk {
    $sdk = $env:ANDROID_SDK_ROOT
    if (-not $sdk) { $sdk = $env:ANDROID_HOME }
    if (-not $sdk) {
        $localProperties = Join-Path $repoRoot 'local.properties'
        if (Test-Path -LiteralPath $localProperties) {
            $line = [System.IO.File]::ReadAllLines($localProperties) | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
            if ($line -match '^\s*sdk\.dir\s*=(.*)$') {
                $sdk = $Matches[1].Trim().Replace('\:', ':').Replace('\\', '\')
            }
        }
    }
    return $sdk
}

$sdkPath = Find-AndroidSdk
if (-not $sdkPath -or -not (Test-Path -LiteralPath $sdkPath)) {
    Stop-Release 'Android SDK not found; set ANDROID_SDK_ROOT or ANDROID_HOME.'
}
$buildTools = Join-Path $sdkPath 'build-tools'
$apksigner = $null
if (Test-Path -LiteralPath $buildTools) {
    $apksigner = Get-ChildItem -LiteralPath $buildTools -Directory |
        Sort-Object { [version]($_.Name -replace '-.*$', '') } -Descending |
        ForEach-Object {
            $candidate = Join-Path $_.FullName 'apksigner.bat'
            if (-not (Test-Path -LiteralPath $candidate)) { $candidate = Join-Path $_.FullName 'apksigner.exe' }
            if (Test-Path -LiteralPath $candidate) { return $candidate }
        } | Select-Object -First 1
}
if (-not $apksigner) { Stop-Release "apksigner not found under $buildTools" }
Write-Host "Verifying signature: $apksigner"
& $apksigner verify $apkSource
if ($LASTEXITCODE -ne 0) {
    if ($needsGradleWrite) { Write-Utf8 $gradlePath $gradleText }
    Stop-Release 'APK signature verification failed.'
}

# ---------------------------------------------------------------------------
# 8. Archive the APK and write update.json.
# ---------------------------------------------------------------------------
New-Item -ItemType Directory -Path $downloadDir, (Split-Path $manifestPath -Parent) -Force | Out-Null
Copy-Item -LiteralPath $apkSource -Destination $apkDestination -Force
$sha256 = (Get-FileHash -LiteralPath $apkDestination -Algorithm SHA256).Hash.ToLowerInvariant()
$publishedAt = [DateTimeOffset]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
$downloadUrl = "$siteOrigin/downloads/$fileName"

$manifestObj = [ordered]@{
    app_id                         = $appId
    app_name                       = $appConfig.name
    package_name                   = $applicationId
    version_name                   = $targetName
    version_code                   = $targetCode
    minimum_supported_version_code = $MinimumSupportedVersionCode
    force_update                   = [bool]$ForceUpdate
    published_at                   = $publishedAt
    release_notes                  = @($ReleaseNotes)
    download_url                   = $downloadUrl
    sha256                         = $sha256
}
Write-Utf8 $manifestPath (($manifestObj | ConvertTo-Json -Depth 10) + [Environment]::NewLine)
Write-Host "Wrote $manifestPath"

# ---------------------------------------------------------------------------
# 9. Merge dist/apps.json, preserving every other app.
#    (Avoids -AsHashtable, which is PowerShell 7 only and silently resets the
#    catalog to a single app when run under Windows PowerShell 5.1.)
# ---------------------------------------------------------------------------
$appsJsonPath = Join-Path $distRoot 'apps.json'
$appsTable = [ordered]@{}
if (Test-Path -LiteralPath $appsJsonPath -PathType Leaf) {
    try {
        $existing = Read-Utf8 $appsJsonPath | ConvertFrom-Json
        if ($existing -and $existing.apps) {
            foreach ($prop in $existing.apps.PSObject.Properties) {
                $entryTable = [ordered]@{}
                foreach ($field in $prop.Value.PSObject.Properties) {
                    if ($field.Name -eq 'releaseNotes') {
                        # ConvertFrom-Json under 5.1 unwraps single element arrays; force it back.
                        $entryTable[$field.Name] = @($field.Value)
                    } else {
                        $entryTable[$field.Name] = $field.Value
                    }
                }
                $appsTable[$prop.Name] = $entryTable
            }
        }
    } catch {
        Stop-Release "could not parse dist/apps.json, refusing to overwrite other apps: $($_.Exception.Message)"
    }
}
$appsTable[$appId] = [ordered]@{
    id           = $appId
    category     = $appConfig.category
    name         = $appConfig.name
    tagline      = $appConfig.tagline
    description  = $appConfig.description
    packageName  = $applicationId
    downloadUrl  = $downloadUrl
    versionName  = $targetName
    sha256       = $sha256
    publishedAt  = $publishedAt
    versionCode  = $targetCode
    apiUrl       = "$siteOrigin/$expectedManifestSubpath"
    releaseNotes = @($ReleaseNotes)
}
$catalogObj = [ordered]@{ apps = $appsTable; updated_at = $publishedAt }
Write-Utf8 $appsJsonPath (($catalogObj | ConvertTo-Json -Depth 10) + [Environment]::NewLine)
Write-Host "Updated $appsJsonPath ($(@($appsTable.Keys).Count) app(s) in catalog)"

Write-Host ''
Write-Host "Version:      v$targetName ($targetCode)"
Write-Host "APK:          $apkDestination"
Write-Host "SHA-256:      $sha256"
Write-Host "Download URL: $downloadUrl"
Write-Host ''

# ---------------------------------------------------------------------------
# 10. Upload.
# ---------------------------------------------------------------------------
if ($PrepareOnly) {
    Write-Host 'PrepareOnly set: local release files prepared, nothing uploaded.'
} else {
    Write-Host 'Uploading to the site...'
    try {
        & $deployScript -DistDirectory $distRoot -SpecificApkFileName $fileName
    } catch {
        Stop-Release ("upload failed: $($_.Exception.Message). " +
            'Local files are ready; run deploy-release.ps1 to retry the upload without rebuilding.')
    }
}

# ---------------------------------------------------------------------------
# 11. Post-upload self check: the public manifest must already serve this build.
# ---------------------------------------------------------------------------
if (-not $PrepareOnly -and -not $SkipVerify) {
    Write-Host "Checking $clientUpdateUrl ..."
    $online = $null
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        try {
            $response = Invoke-WebRequest -Uri $clientUpdateUrl -UseBasicParsing -TimeoutSec 20
            $online = $response.Content | ConvertFrom-Json
            if ($online.version_code -eq $targetCode) { break }
        } catch {
            $online = $null
        }
        Start-Sleep -Seconds 2
    }
    if (-not $online) {
        Stop-Release "could not fetch the public manifest: $clientUpdateUrl"
    }
    if ($online.version_code -ne $targetCode) {
        Stop-Release ("public manifest still reports version_code $($online.version_code), expected $targetCode. " +
            'This is usually CDN caching; retry later.')
    }
    if ($online.sha256 -ne $sha256) {
        Stop-Release 'public manifest sha256 does not match this build.'
    }
    Write-Host 'Public manifest verified.'
}

# ---------------------------------------------------------------------------
# 12. Commit the version bump and tag.
# ---------------------------------------------------------------------------
if ($needsGradleWrite) {
    Invoke-Git @('add', '--', 'app/build.gradle.kts') | Out-Null
    Invoke-Git @('commit', '-m', "Release $targetName (versionCode $targetCode)") | ForEach-Object { Write-Host $_ }
} else {
    Write-Host 'Version unchanged; nothing to commit.'
}

if ($PrepareOnly) {
    # Nothing left the machine, so do not tag an unreleased version.
    Write-Host 'PrepareOnly set: tag and push skipped.'
} else {
    $tagName = "v$targetName"
    $existingTag = & git -C $repoRoot tag --list $tagName
    if ($existingTag) {
        Write-Host "Tag $tagName already exists; skipped."
    } else {
        Invoke-Git @('tag', $tagName) | Out-Null
        Write-Host "Created tag $tagName"
    }

    if ($Push) {
        Invoke-Git @('push', 'origin', 'HEAD:main') | Out-Null
        Invoke-Git @('push', 'origin', '--tags') | Out-Null
        Write-Host 'Pushed to origin.'
    } else {
        Write-Host 'Not pushed. Run: git push origin main --tags'
    }
}

Write-Host ''
if ($PrepareOnly) {
    Write-Host "Done: $($appConfig.name) v$targetName prepared in $distRoot (not uploaded)."
} else {
    Write-Host "Done: $($appConfig.name) v$targetName published to $siteOrigin."
}
