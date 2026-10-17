# 拖延之门 v0.2.0

一个通过轻量交互打断无意识刷屏的 Android 应用。

不封锁娱乐 App，而是在你进入或持续使用目标 App 时，插入一次短暂、可绕过的主动选择。

> 不替你决定，只让这次继续变成一个清醒的决定。

## 当前功能

- 从已安装应用中选择需要干预的目标 App，支持搜索与字母分组
- 支持「打开时立即触发」，或连续使用 1 / 5 / 10 / 15 / 20 分钟后触发
- 每个目标 App 一条独立规则：生效时段、生效星期、重复间隔、现实出口
- 使用悬浮窗展示几乎全屏的「门」，沿轨迹描摹、长按、呼吸等方式推开
- **24 种推开方式**，分简易 / 中等 / 困难三档，可按难度档随机或手动勾选组合
- 一键跳转到用户选择的替代活动（现实出口）
- 长按紧急绕过，暂停 30 分钟
- 数据完全保存在本机，不使用无障碍服务
- 应用内检查更新（自建更新源）

## 推开方式（24 种）

| 档位 | 方式 |
|---|---|
| 简易 9 种 | 描摹轨迹、长按、呼吸、点按序列、拖滑块、向内推合、翻转手机、读一句话、倒计时 |
| 中等 8 种 | 记忆点阵、心算、计数、三段式门栓、推钥匙、目标扫描、旋钮锁、意图确认 |
| 困难 7 种 | 多阶段门锁、连续扫描、反向记忆、拼合门板、路线规划、记忆+旋钮、反思式等待 |

难度档 `PROGRESSIVE` 会在 10 分钟内反复触发时自动升档，`CUSTOM` 则完全按手动勾选组合。

开启系统读屏（触摸浏览）时，所有依赖手势的推开方式会降级为「单击确认」，避免读屏用户被永久卡在门上。

## 构建

环境要求：JDK 17+、Android SDK 35。

```powershell
./gradlew.bat assembleDebug
```

安装包输出：

- 调试包：`app/build/outputs/apk/debug/app-debug.apk`
- 发布包：`app/build/outputs/apk/release/app-release.apk`（需要 `keystore.properties`）

## 发布

发布脚本位于 `scripts/`，需要与 `app-release-site` 仓库同级存放。

```powershell
# 完整发布：升版本 → 打包 → 验签 → 生成 update.json → 上传网站 → 提交并打 tag
./scripts/publish-release.cmd -ReleaseNotesFile notes.txt

# 只准备本地文件，不上传（也不打 tag）
./scripts/publish-release.ps1 -PrepareOnly -ReleaseNotesFile notes.txt

# 上传失败后重试（不重新打包）
./scripts/deploy-release.cmd
```

多行更新说明用 `-ReleaseNotesFile`（一行一条）或按提示逐行输入——`.cmd` 走的是
`powershell -File`，不支持 `-ReleaseNotes "a","b"` 这种数组写法。详见 `scripts/README.md`。

## 首次使用

1. 选择目标 App 和替代活动。
2. 授予「使用情况访问权限」。
3. 授予「显示在其他应用上层」权限。
4. Android 13 及以上允许运行状态通知。
5. 点击「开始守门」。

## 已知限制

- 各厂商系统的后台限制可能导致监控服务被终止，需要允许应用后台保持运行。
- 为便于测试，最短触发间隔提供 1 分钟。
- 守门开启时，应用更新或设备重启后会在权限有效的前提下自动恢复。
- 设置页提供服务心跳、权限、电池优化和厂商后台管理诊断。
- 系统「减少动态效果」目前尚未接入，动画时长仍是固定值。
- 读屏模式采用降级策略（单击确认），并非对这 24 种交互的完整无障碍实现。

## 包名变更说明

0.2.0 起 `applicationId` 由 `com.example.procrastinationgate` 改为 `top.acupofttu.timecontroller`。
由于包名变化，Android 会将其视为另一个应用：从 0.1.x 升级时**新版会与旧版并存**，需要手动卸载旧版。
