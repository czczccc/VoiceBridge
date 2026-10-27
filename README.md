# VoiceBridge

Android ↔ Windows 局域网语音文本与纯文本剪切板工具。

语音识别由手机当前输入法完成。VoiceBridge 计划将 Android 文本框的变化同步到 Windows 用户确认的输入位置，并支持双向纯文本剪切板。

**当前进度**：TASK-000 ~ TASK-008 的代码均已实现并通过 CI（通知入口跳转已真机确认，权限拒绝场景待验证）；用户已手动验证到 TASK-007 之前。语音优先主界面和「远程换行不触发回车」已通过 CI 与真机验证（键盘、返回、设置、通知入口正常；长段落中途停顿不再回车）；剪切板、托盘与设置尚未实现。实施顺序与实际验证结果见 [Docs/TASKS.md](Docs/TASKS.md)。

## 仓库结构

```text
VoiceBridge/
├─ .github/workflows/build.yml   Android / Windows 构建和检查
├─ Docs/                        产品、架构、协议、安全及任务文档
├─ android/                     Kotlin + Jetpack Compose
└─ windows/                     Tauri 2 + Rust + React + TypeScript
```

## 开发与验证分工

- 本地：编码、文档维护及不需要编译的格式和配置检查。
- GitHub Actions：编译 Android / Windows、打包、静态分析和自动化测试。
- 用户：下载 CI 产物，在 Android 手机真机及 Windows 电脑上安装测试，反馈实际结果。

默认不在本机编译，不要求本地安装 Android SDK。CI 通过与真机测试通过分别记录。

## 使用 GitHub Actions 构建

无需在自己的电脑安装 Android SDK 或 Windows 编译工具。

1. 将代码推送到 GitHub 的 `main` 分支，或创建 Pull Request；也可以在仓库 **Actions → Build apps → Run workflow** 手动触发。
2. 等待 Android 与 Windows 构建检查完成。main 分支还会构建固定签名 APK，并在两端检查通过后发布到 [Android 测试版 Releases](https://github.com/czczccc/VoiceBridge/releases/tag/android-testing)。首次构建需下载依赖。
3. 手机首次安装：从上述 Releases 下载 `VoiceBridge-数字.apk` 中版本号最大的包。后续打开 App 自动检查，点击下载和安装即可更新，不用再去 GitHub 查找。
4. Windows 首次安装：从 **Artifacts** 的 `voicebridge-windows-x64` 或 [Windows 测试版 Releases](https://github.com/czczccc/VoiceBridge/releases/tag/windows-testing) 下载并安装。之后打开应用，在「版本更新」点「检查更新」→「下载并安装」，更新在应用内完成，不用再手动下载。
   - `voicebridge-android-debug`：解压得到 `app-debug.apk`，仅供诊断，日常手机测试请用 Releases 固定签名包。
   - `android-check-reports`：Android Lint 等检查报告；可用于排查失败。

附件保留 14 天，Releases 测试包持续保留。仅发布任务持有仓库内容写权限，PR 不使用签名密钥、不发布。Windows 安装包暂未配置代码签名。CI 成功不代表真机启动、输入法兼容或端到端验收通过。`voicebridge-android-debug` 仅供诊断，签名与测试更新渠道不同；日常手机测试请安装 Releases 固定签名包。

### 一次性配置：Android 测试签名

必须固定 APK 签名才能连续覆盖安装。四个仓库 Secrets：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。无需手工编辑 Actions YAML，也不需要自己申请 GitHub Token；发布使用 Actions 内置的 `GITHUB_TOKEN`。

仓库提供 [初始化脚本](scripts/setup-android-signing.ps1)，在安装 GitHub CLI 并执行 `gh auth login` 后，在仓库根 PowerShell 运行一次：

```powershell
.\scripts\setup-android-signing.ps1
```

脚本使用本机 JDK 的 `keytool` 生成密钥，将四个 Secrets 上传到仓库，密钥与密码保存在用户目录 `.voicebridge-signing/`（限制为当前用户访问，不进入仓库）。请安全备份，后续不要重新生成或替换；丢失密钥会影响已有安装的更新。此步骤只生成签名，不在本机编译。

也可手动进入 **Settings → Secrets and variables → Actions → New repository secret** 配置上述四项。若缺少 Secrets，main 的签名步骤会明确失败，不发布临时签名更新。若仓库禁用 Actions，需要启用；若组织策略禁止 `contents: write`，需要允许发布任务创建 Releases。

手机首次应用内安装需在系统设置允许 VoiceBridge“安装未知应用”。Android 每次仍要求用户确认安装，普通应用不能静默替换自己。需要手机能访问 GitHub 下载服务。公开更新请求仅包含获取版本和 APK 所需信息，不上传输入文本或剪切板。

### CI 执行内容

| 平台 | 环境 | 构建与检查 |
| --- | --- | --- |
| Android | Ubuntu 24.04、Temurin JDK 17、SDK 35 / Build Tools 35.0.0、Gradle Wrapper 8.11.1 | debug 构建/Lint/单元测试；main 增加固定签名 release 构建/Lint/单元测试 |
| Windows x64 | Windows Server 2022、Node 22、Rust stable、MSVC、WebView2 | TypeScript / Prettier、Rustfmt / Clippy、`cargo test`、Tauri release + NSIS |

依赖通过 `windows/package-lock.json` 与 `windows/src-tauri/Cargo.lock` 锁定。Android 插件和直接依赖使用固定版本，Gradle Wrapper JAR 与发行包均有 SHA-256 校验。Android 与 Rust 均已包含协议、配对、发现、会话与注入相关单元测试，由 CI 执行。

## 本地构建命令参考（默认使用 CI，不在本机执行）

### Windows

以下仅供将来主动选择本地构建时参考，不属于当前工作流程。需要 Windows 10/11 x64、Node.js ≥ 22.12、Rust stable（MSVC 工具链）、Visual Studio Build Tools 的 **Desktop development with C++** 和 Windows SDK，以及 WebView2 Runtime。

```powershell
cd windows
npm ci
npm run tauri -- dev
```

完整构建（React 前端 → Rust 应用 → Windows 安装包）：

```powershell
npm run tauri -- build --bundles nsis --ci -- --locked
```

输出：`windows/src-tauri/target/release/bundle/nsis/`。`cargo build` 仅构建 Rust 工程；完整应用建议使用上述 Tauri 命令，它会先执行前端构建。

其他检查（在 `windows/` 执行）：

```powershell
npm run format:check
npm run build
cargo fmt --manifest-path src-tauri/Cargo.toml --all -- --check
cargo clippy --manifest-path src-tauri/Cargo.toml --locked --all-targets -- -D warnings
cargo test --manifest-path src-tauri/Cargo.toml --locked
```

### Android

需要 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0；可以用 Android Studio 打开 `android/`。设置 `ANDROID_HOME` 或通过本机的 `android/local.properties` 指定 `sdk.dir`，不要提交本机路径。无需全局安装 Gradle。

```powershell
cd android
.\gradlew.bat --no-daemon assembleDebug lintDebug testDebugUnitTest
```

macOS/Linux 使用 `bash ./gradlew`。输出 APK：`android/app/build/outputs/apk/debug/app-debug.apk`。最低系统版本为 Android 8.0（API 26）；这里只说明构建配置，设备兼容性仍需实测。

## 文档入口

- [产品概览](Docs/README.md)
- [需求与范围](Docs/PRD.md)
- [架构与目录](Docs/ARCHITECTURE.md)
- [通信协议](Docs/PROTOCOL.md)
- [安全与隐私](Docs/SECURITY.md)
- [唯一实施任务清单及验证记录](Docs/TASKS.md)
- [MVP 验收测试](Docs/ACCEPTANCE_TESTS.md)
- [开发约束](Docs/CLAUDE.md)

正式局域网功能必须使用认证与证书指纹验证；Windows 输入必须由用户启动会话并检查焦点。不得自行录音或接入 ASR，不得自动点击第三方输入法麦克风，不得反复使用 Ctrl+A 覆盖目标文档。
