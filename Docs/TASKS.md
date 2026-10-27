# TASKS.md — 唯一实施任务清单

> Claude Code 必须以本文件作为当前实施顺序的唯一来源。一次只完成一个阶段；每阶段完成后运行可用的构建/测试，更新本文件，并说明未完成项。不要跳过失败测试，不要虚构构建成功。

## 开发约束

- 用户约定：本地编码；GitHub Actions 负责编译、构建和自动化测试；用户下载 APK 用 Android 手机真机测试，并在 Windows 验证桌面产物。默认不在本机编译。CI 结果和真机结果分别记录。

- Android：Kotlin + Jetpack Compose。
- Windows：Tauri 2 + Rust + React + TypeScript。
- 局域网：WSS；MVP 可先用固定 IP/端口开发，但正式配对必须验证证书指纹。
- 语音识别：只复用用户当前 Android 输入法；禁止接入 `SpeechRecognizer`、自建 ASR 或自动点击 IME 麦克风。
- 文本同步：完整快照 + `sessionId` + 递增 `seq`。
- Windows 输入：必须有用户明确启动的输入会话、焦点保护和停止机制。
- 不允许每次快照都 `Ctrl+A` 后粘贴。
- 不在日志记录文本正文、剪切板正文、密钥。
- 不做 iOS、图片/文件同步或云端中继。
- 任何无法安全实现的功能都要报告限制，不要用危险的模拟点击或无条件粘贴来“假装完成”。

## TASK-000：仓库与构建基线
**目标**
- 建立清晰的 monorepo 结构。
- 添加 README、PRD、ARCHITECTURE、PROTOCOL、SECURITY、TASKS、ACCEPTANCE_TESTS、CLAUDE 文档。
- 初始化 Android 与 Tauri 2 项目，确认开发环境要求。

**验收**
- 两端都有最小启动页面。
- 文档与目录结构一致。
- 记录实际构建命令和环境依赖。
- 不实现业务功能。

## TASK-001：共享协议模型与状态定义
**目标**
- 在 Rust 与 Kotlin 中定义协议版本、消息类型、错误码、session 状态。
- 实现 JSON 编解码与必需字段验证。

**验收**
- `hello`、`text.snapshot`、`text.ack`、`session.start`、`session.stop`、`clipboard.text`、`error` 均有模型。
- 对缺字段、错误版本、超大文本和非法序号有测试。
- 测试数据与 PROTOCOL.md 一致。

## TASK-002：Windows 本地服务与状态 UI
**目标**
- Windows Rust 端启动局域网服务，UI 显示服务状态、地址与端口。
- 开发阶段允许本机回环测试；正式 LAN 监听需显式开启。
- 不允许未经认证的远程客户端控制输入。

**验收**
- React UI 显示服务运行/停止状态。
- 无效消息被拒绝。
- 服务关闭后端口释放。
- 日志不包含消息正文。

## TASK-003：配对与安全连接
**目标**
- 实现一次性配对码/二维码、证书指纹校验、设备授权列表与撤销。
- Android 凭据使用安全存储。
- 实现 WSS 与连接认证。

**验收**
- 正确配对成功。
- 错误指纹、错误令牌、过期 nonce 和撤销设备均失败。
- 配对码过期后不能复用。
- 未实现安全认证前，不得开放生产 LAN 输入注入。

## TASK-004：Android 输入法文本框
**目标**
- 建立 Compose 多行输入页。
- 打开页面时聚焦文本框，并在系统允许时弹出当前 IME。
- 监听文本、光标与可用的 composition 状态。

**验收**
- 不强制设置某一输入法。
- 微信输入法/至少一种第三方输入法可输入文本。
- 语音识别产生的文本进入输入框。
- 文本编辑、删除、替换、清空都能被状态层观察到。
- App 不自行录音、不调用 ASR。

## TASK-005：Android 文本快照与 WebSocket 发送
**目标**
- 为文本变化生成完整快照。
- 添加 30–80 ms 可配置合并窗口、递增序号、sessionId 与重连逻辑。

**验收**
- 快速连续输入不产生过多重复快照。
- 中间文本被整句修正时，最新快照能表达正确完整文本。
- 断线后旧 session 不自动恢复。
- 消息过大时显示错误，不静默截断。

## TASK-006：Windows 输入会话与焦点保护
**目标**
- 实现 `Idle/Armed/Active/PausedFocus/Stopping/Error` 状态机。
- 用户明确启动会话时捕获目标窗口。
- 每次注入前检查焦点；焦点变化后暂停。
- 建立独立的 `TextInjectionBackend` trait/interface。

**验收**
- 未启动会话时不注入任何文字。
- 焦点变化后旧会话不再写入。
- 停止会话后延迟到达的旧消息被丢弃。
- 不实现 `Ctrl+A + 全文粘贴` 更新方式。
- 对不支持的目标应用返回清晰错误。

## TASK-007：实时文本差异更新
**目标**
- Windows 端接收快照、校验序号并应用文本差异。
- MVP 目标场景限定为会话开始时光标位于输入末尾，且用户不移动光标。
- 安全处理 Unicode、emoji、换行和整句回改。

**验收**
- 从空文本逐步输入时无重复。
- 尾部删除、整句替换、标点修正可正确更新。
- Unicode 测试通过。
- 焦点不确定时暂停，不尝试写入其他窗口。
- 记录每次应用结果的错误码，不记录正文。

## TASK-008：通知栏快捷入口
**目标**
- Android 提供“打开语音输入”通知操作。
- 用户点击后打开 Activity、聚焦输入框并请求显示 IME。

**验收**
- 用户点击通知可进入输入页面。
- 系统允许时键盘弹出；不允许时页面仍可手动点击输入框。
- 不从后台强制弹出键盘，不自动点击 IME 麦克风。
- 通知权限被拒绝时 App 仍可通过主界面使用。

## TASK-009：双向纯文本剪切板
**目标**
- Windows 监听文本剪切板变化。
- Android 端在前台或用户触发时同步文本。
- 实现方向开关、事件 ID、来源标记与防回环。

**验收**
- 两个方向均可同步纯文本。
- 同步事件不无限循环。
- 相同内容被用户再次主动复制仍可发送。
- Android 后台限制场景下提示用户，不假装持续读取成功。
- 不同步图片和文件。

## TASK-010：托盘、设置与错误提示
**目标**
- Windows 托盘、连接状态、输入会话启停、剪切板开关、退出。
- Android 设置页包含目标电脑、连接状态、自动同步开关、复制/发送按钮。
- 添加脱敏诊断信息。

**验收**
- 关闭主窗口按设定最小化到托盘。
- 退出应用会停止服务和输入注入。
- 诊断信息不包含文本、密钥或剪切板正文。

## TASK-011：输入法兼容性与集成测试
**目标**
- 测试微信输入法、百度输入法、搜狗输入法、豆包输入法（可用设备/版本）。
- 测试 Windows 记事本、浏览器输入框、VS Code 普通编辑区。

**验收**
- 每种可测输入法记录版本、设备、语音结果是增量提交还是整句提交、回改表现。
- 至少完成一条端到端流程：手机语音输入 → Android 文本框 → WSS → Windows 测试输入框。
- 不宣称未实际测试的兼容性。
- 更新 ACCEPTANCE_TESTS.md 的结果与已知限制。

## TASK-012：打包、发布说明与隐私审查
**目标**
- 生成 Windows 安装包与 Android debug/release 构建。
- 完善首次配对引导、权限说明、隐私说明、卸载/撤销说明。

**验收**
- 全新环境按 README 可构建。
- 安装包启动、配对、语音输入、剪切板同步、退出流程均可验证。
- 发布说明列出系统版本、测试环境、已知限制。
- 所有安全验收项通过或明确标记为发布阻断问题。

## 用户追加：Android 测试版应用内更新（随 TASK-000 交付）

用户明确要求追加此功能，优先解决真机测试每次手动寻找 APK 的问题；该追加不代表 TASK-001 至 TASK-012 已完成。

已实现代码，尚待 CI / 真机验证：
- App 启动时检查公开 GitHub Releases 固定渠道；支持检查、下载、校验和打开 Android 系统安装界面。
- 下载前用户点击确认；不后台静默下载，不静默安装。下载失败可重试，不阻断现有页面。
- SHA-256、包名、递增版本、APK 签名校验；下载限 100 MiB，清单限 16 KiB。异步任务随 ViewModel 清理取消。
- Actions 使用固定签名与 run_number 版本号，main 双端检查通过后发布测试 APK 和 update.json；PR 不发布、不访问密钥。
- `scripts/setup-android-signing.ps1` 一次性生成并配置四个签名 Secrets；不生成本地构建。用户已执行成功，已通过 `gh secret list` 确认四项名称存在（不读取密钥值）。
- 更新清单正常数据、非法 URL、版本、大小和摘要校验已添加 JUnit 测试，等待 CI。
- 已验证（不编译）：actionlint 检查更新后的 workflow 通过，嵌入 Python 语法、签名脚本 PowerShell 语法、Android XML、根文档链接和 `git diff --check` 通过。
- 已通过 GitHub 只读 API 确认 `czczccc/VoiceBridge` 为公开仓库；用户已安装并登录 GitHub CLI，签名初始化和 Secrets 上传完成。

待用户真机验收：
- [ ] 首次固定签名 APK 安装与启动；可显示版本、检查更新。
- [ ] 发布更高版本后 App 检出，下载并覆盖安装，应用数据保留。
- [ ] 拒绝未知来源权限、取消安装后可返回重试。
- [ ] GitHub 不可达时显示更新失败，现有应用仍可使用。
- [ ] 错误摘要、错误签名、旧版本不安装。

## TASK-000 实施与验证记录

状态：两端 CI 构建与检查通过，测试包已发布；等待用户真机安装与欢迎页启动验证，未进入 TASK-001。

已实现：
- 保留 `Docs/` 为文档目录，根 `README.md` 提供导航、依赖和构建命令，根 `CLAUDE.md` 指向完整项目约束。
- Android：Kotlin / Compose 欢迎页，AGP 8.9.2、Kotlin 2.1.20、SDK 35、Gradle Wrapper 8.11.1；按用户追加应用内更新网络请求；未添加录音或输入同步功能。
- Windows：Tauri 2 / Rust / React / TypeScript 欢迎页，npm 与 Cargo 锁文件；未添加输入注入或网络服务。
- `.github/workflows/build.yml`：推送 main、PR、手动触发；Android debug APK 和 Windows x64 NSIS 安装包保存为附件 14 天，并执行可用的静态检查和测试命令。

已验证：
- 本机 Windows 11 x64，Node 24.21.0、npm 11.19.0、Rust 1.98.1 MSVC、JDK 21；MSVC 和 WebView2 可用。
- 明确分工前曾执行 `npm install`、`npm run build`（含 TypeScript 检查）和 `npm run format:check`，结果通过。随后尝试 `npm ci` 因本地 Tauri 编译进程占用 CLI 文件而失败（EPERM）；未将其记为通过。后续安装与构建验证由 CI 执行。
- `cargo fmt --manifest-path windows/src-tauri/Cargo.toml --all -- --check` 通过。
- actionlint 1.7.12 检查 GitHub Actions 配置通过；Android XML、项目 JSON、Gradle 启动脚本语法检查通过。
- 官方 Gradle Wrapper JAR SHA-256 与官方值一致，`bash ./gradlew --version` 下载并校验发行包，成功启动 Gradle 8.11.1。

未验证 / 剩余风险：
- 用户明确分工后已停止本地构建；构建结果以 GitHub Actions 为准。
- 首轮 CI 的 Android SDK 初始化失败（默认安装已下架 tools 包），已明确使用 platform-tools；第二轮 CI 40457483697 全部成功。
- 已验证：Android debug/release 构建、Lint、各 3 个 JUnit 测试（零失败）；Windows 前端类型/格式、Rustfmt、Tauri release + NSIS、Clippy、cargo test 命令成功（Rust 尚无业务测试）。
- 已发布 Android 固定签名 0.1.0-test.2（VoiceBridge-2.apk），CI 产物 SHA-256 与大小匹配更新清单；Releases 上传成功。
- 两端页面尚无真实设备上的启动验证，尚无业务单元测试；无测试不能视为业务验收通过。
- Windows 安装程序暂未代码签名；Android 固定签名 Secrets 已用于成功构建和发布。构建附件和 Releases 均用于开发验证。
- 当前运行存在 Actions Node 20 / setup-java v4 迁移提示，以及 gradlew 可执行位提示（Action 自动处理）；不影响本次构建结果，后续维护处理。

假设：
- GitHub 托管 runner 可访问 npm、crates.io、Google Maven 与 Gradle 发行包服务，并可下载 NSIS。
- CI 成功仅证明构建和所执行检查通过，不代替后续输入法兼容、焦点保护和安全验收。

## 当前状态

- [ ] TASK-000 仓库与构建基线（双端 CI 已通过并发布，等待用户设备启动验证）
- [ ] TASK-001 协议模型
- [ ] TASK-002 Windows 服务与 UI
- [ ] TASK-003 配对与安全连接
- [ ] TASK-004 Android 输入法文本框
- [ ] TASK-005 Android 快照发送
- [ ] TASK-006 Windows 输入会话与焦点保护
- [ ] TASK-007 实时文本差异更新
- [ ] TASK-008 通知栏快捷入口
- [ ] TASK-009 双向纯文本剪切板
- [ ] TASK-010 托盘与设置
- [ ] TASK-011 兼容性测试
- [ ] TASK-012 打包与发布
