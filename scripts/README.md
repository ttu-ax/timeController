# 发布脚本

把 `timeController` 打包并发布到 `https://acupofttu.top`。

## 依赖

- 与 `app-release-site` 仓库同级存放（默认查找 `..\app-release-site`，也可以用
  `-ReleaseSiteDirectory` 或环境变量 `TIME_CONTROLLER_RELEASE_SITE` 指定）。
- `keystore.properties` 已配置且证书文件存在。
- 环境变量 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME` 指向 Android SDK。
- 站点仓库 `scripts/deploy-site.ps1` 所需的 SSH 私钥。

## 常用命令

```powershell
# 完整发布:升版本 → 打包 → 验签 → 写 update.json → 上传 → 线上自检 → 提交并打 tag
.\scripts\publish-release.cmd -ReleaseNotesFile notes.txt

# 只准备本地文件,不上传(也不会打 tag)
.\scripts\publish-release.ps1 -PrepareOnly -ReleaseNotesFile notes.txt

# 指定版本号
.\scripts\publish-release.ps1 -VersionName 0.3.0 -VersionCode 5 -ReleaseNotesFile notes.txt

# 上传失败后重试(不重新打包、不改版本号)
.\scripts\deploy-release.cmd

# 发布并推送到 GitHub
.\scripts\publish-release.ps1 -ReleaseNotesFile notes.txt -Push
```

## 更新说明怎么写

`.cmd` 通过 `powershell -File` 启动脚本，这种调用方式**不支持数组参数**：
写成 `-ReleaseNotes "a","b"` 会被当成一条内容为 `a,b` 的说明。

所以多行说明请用以下任一方式：

1. **`-ReleaseNotesFile`（推荐）**：文件一行一条，UTF-8 编码。

   ```
   修复反思式等待里选了「离开」却仍被推回门内的问题
   路线规划不再出现无解死局，并支持点上一格撤销
   双指捏合改为单指向内推合，单手可用
   ```

2. **不传参数**：脚本会逐行提示输入，空行结束。适合临时发布。

仅当直接调用 `.ps1`（例如在 `pwsh` 里用 `&` 调用）时，`-ReleaseNotes "a","b"`
这种数组写法才有效。

## 版本号规则

| 情况 | 行为 |
|---|---|
| `app/build.gradle.kts` 的 versionCode **大于**线上已发布版本 | 直接发布当前版本，不再叠加（适用于「已经手动改好版本号」） |
| versionCode **等于或小于**线上版本 | 自动 +1，版本名 patch +1（0.2.0 → 0.2.1） |
| 显式传入 `-VersionName` / `-VersionCode` | 用传入值，但必须大于线上版本 |

## 前置校验

脚本动手之前会拦住这些情况，避免发出装不上的包：

- 不在 `main` 分支，或有已暂存未提交的改动（`-SkipGitChecks` 可跳过）。
- `app-registry.json` 的 `projectDir` 不指向当前目录。
- `app-registry.json` 的 `packageName` 和 `build.gradle.kts` 的 `applicationId` 不一致
  —— 这会让 update.json 带上错误包名。
- `UpdateChecker.kt` 里的 `UPDATE_URL` 和本次发布地址不一致 —— 发布出去客户端也收不到更新。
- 目标 versionCode 不大于线上已发布版本。
- Gradle 打包失败或 `apksigner` 验签失败（会把版本号回滚）。

## 上传后自检

除 `-PrepareOnly` / `-SkipVerify` 外，脚本上传后会请求
`https://acupofttu.top/apps/time-controller/update.json`，确认 `version_code` 和
`sha256` 已经是本次构建的值。这样「命令行说成功了但站点还是旧的」会被当场发现。

## 产物位置

| 文件 | 位置 |
|---|---|
| APK | `app-release-site/dist/downloads/time-controller-v<版本>.apk` |
| 应用清单 | `app-release-site/dist/apps/time-controller/update.json` |
| 站点目录 | `app-release-site/dist/apps.json`（合并写入，保留其它应用） |

## 两个已知的坑

**1. `dist/apps.json` 是多个应用共用的目录文件。**
本脚本读入现有内容再合并，不会覆盖其它应用。但站点仓库里的 `publish-app.ps1`
使用了 PowerShell 7 才支持的 `ConvertFrom-Json -AsHashtable`；本机是 Windows
PowerShell 5.1，该参数会抛错并被 `catch` 吞掉，结果是把目录重置成只剩当前应用。
发布 timeController 请用本目录下的脚本。

**2. 脚本文件必须保持纯 ASCII。**
Windows PowerShell 5.1 读取**无 BOM** 的脚本时按系统 ANSI（中文环境是 GBK）解码，
脚本里出现中文会导致语法错误。这就是 `.ps1` / `.cmd` 里消息都是英文的原因。
`README.md` 是文档，不受影响。
