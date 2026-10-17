[CmdletBinding()]
param(
    [string]$ReleaseSiteDirectory,
    [string]$PublishOrigin = 'https://acupofttu.top',
    [switch]$SkipVerify
)

# Re-uploads an already prepared release. Does not rebuild and does not touch
# the version number. Use it after publish-release.ps1 built the APK but the
# upload failed.
#
# NOTE: This file must stay pure ASCII. Windows PowerShell 5.1 reads BOM-less
# scripts with the system ANSI code page, so non-ASCII literals would corrupt
# parsing on a Chinese Windows install.

$ErrorActionPreference = 'Stop'

$appId = 'time-controller'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$siteOrigin = $PublishOrigin.TrimEnd('/')
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Stop-Deploy([string]$Message) {
    throw "DEPLOY ABORTED: $Message"
}

function Read-Utf8([string]$Path) {
    return $utf8NoBom.GetString([System.IO.File]::ReadAllBytes($Path))
}

if (-not $ReleaseSiteDirectory) { $ReleaseSiteDirectory = $env:TIME_CONTROLLER_RELEASE_SITE }
if (-not $ReleaseSiteDirectory) {
    $ReleaseSiteDirectory = Join-Path (Split-Path $repoRoot -Parent) 'app-release-site'
}
if (-not (Test-Path -LiteralPath $ReleaseSiteDirectory -PathType Container)) {
    Stop-Deploy "release site directory not found: $ReleaseSiteDirectory"
}
$ReleaseSiteDirectory = (Resolve-Path -LiteralPath $ReleaseSiteDirectory).Path
$distRoot = Join-Path $ReleaseSiteDirectory 'dist'
$deployScript = Join-Path $ReleaseSiteDirectory 'scripts\deploy-site.ps1'
$manifestPath = Join-Path $distRoot "apps\$appId\update.json"

foreach ($required in @($deployScript, $manifestPath)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        Stop-Deploy "missing required file: $required (run publish-release.ps1 first)"
    }
}

$manifest = Read-Utf8 $manifestPath | ConvertFrom-Json
$fileName = Split-Path -Leaf ([Uri]$manifest.download_url).AbsolutePath
if (-not $fileName) { Stop-Deploy 'could not derive the APK file name from download_url.' }

$apkPath = Join-Path (Join-Path $distRoot 'downloads') $fileName
if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) {
    Stop-Deploy "prepared APK not found: $apkPath"
}
$hash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
if (-not $manifest.sha256 -or $hash -ne $manifest.sha256.ToLowerInvariant()) {
    Stop-Deploy 'prepared APK does not match the sha256 in update.json; refusing to upload. Re-run publish-release.ps1.'
}

Write-Host "Re-uploading v$($manifest.version_name) (versionCode $($manifest.version_code))"
Write-Host "APK: $apkPath"

try {
    & $deployScript -DistDirectory $distRoot -SpecificApkFileName $fileName
} catch {
    Stop-Deploy "upload failed: $($_.Exception.Message)"
}

if (-not $SkipVerify) {
    $updateUrl = "$siteOrigin/apps/$appId/update.json"
    Write-Host "Checking $updateUrl ..."
    $online = $null
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        try {
            $online = (Invoke-WebRequest -Uri $updateUrl -UseBasicParsing -TimeoutSec 20).Content | ConvertFrom-Json
            if ($online.version_code -eq $manifest.version_code) { break }
        } catch {
            $online = $null
        }
        Start-Sleep -Seconds 2
    }
    if (-not $online) { Stop-Deploy "could not fetch the public manifest: $updateUrl" }
    if ($online.version_code -ne $manifest.version_code) {
        Stop-Deploy "public manifest reports version_code $($online.version_code), expected $($manifest.version_code) (usually CDN caching)."
    }
    Write-Host 'Public manifest verified.'
}

Write-Host "Done: v$($manifest.version_name) uploaded to $siteOrigin"
