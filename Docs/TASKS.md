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
- mDNS 自动发现（Windows 广播、Android 浏览），手动输入地址作为兜底。
- 实现一次性配对会话、证书指纹校验、蓝牙式 6 位数字码核对、设备授权列表与撤销。
- Android 凭据使用 Android Keystore 安全存储。
- 实现 WSS 与连接认证。

**拆分**
- 003a：PROTOCOL.md、两端配对消息模型、共享样例与测试。
- 003b：证书生成、WSS、持久连接与服务循环重写、nonce/配对码计算、token 存储。
- 003c：mDNS 发现、两端配对 UI、设备列表与撤销、手动地址兜底。

**验收**
- 正确配对成功；两端显示的配对码一致。
- 配对码不一致时用户可拒绝，服务器返回 `mismatch` 且不发放 token。
- 错误指纹、错误令牌、过期 nonce 和撤销设备均失败。
- 配对码过期后不能复用；并发配对返回 `busy`。
- 已配对设备重连无需再次确认，撤销后立即失效。
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

## TASK-001 实施与验证记录

状态：已完成。两端模型与测试在 CI 40459998825 编译和运行通过（Android debug/release 各 9 项单元测试零失败，Windows 单元测试、Clippy、NSIS 均通过）。

已验证：
- CI 40459512395 首次运行暴露两端行为差异：Kotlin 会把 JSON 字符串 `"true"` 自动转换为布尔值而放行；已改为严格布尔类型检查，并将整数范围检查收归同一入口。
- CI 40459998825 全绿：Android debug/release 构建、Lint、单元测试（协议 6 项 + 更新 3 项），Windows 前端类型/格式、Rustfmt、单元测试（协议 6 项）、Clippy、NSIS 打包。
- 测试覆盖：8 条共享正常消息往返、20 条共享非法消息、每个必需字段缺失与 null、完整帧 256 KiB 边界、UTF-8 多字节计入长度、seq 边界、UTF-16 代理对边界、未知可选字段、空文本、构造消息的编码校验。
- 已发布 0.1.0-test.4（VoiceBridge-4.apk，7,436,229 字节）；Releases 清单 sha256 与 CI 产物一致。

未验证 / 风险：
- 本轮未启动服务、未认证、未注入文字，也未接入网络；协议解析成功不代表消息已获授权。
- 尚未真机验证从 0.1.0-test.2 应用内检出并覆盖安装 0.1.0-test.4。
- 协议模型与样例已建，后续修改消息字段时需同步更新 `protocol/fixtures/` 与两端实现。

已实现：
- Rust / Kotlin 定义 8 类协议消息（含 hello.ack）、12 个错误码、会话和连接状态；状态转换及运行时旧序号检查留给后续任务。
- JSON 编解码、必需字段和类型校验、版本、UTF-8 完整消息 256 KiB 上限、整数范围、枚举与 UTF-16 范围校验；消息与凭据模型不自动输出正文调试字符串。
- `protocol/fixtures/` 提供两端共用的 8 条正常消息、20 条非法消息样例；各端另测往返、必需字段缺失/null、超限与边界、非法序号、未知可选字段和 Unicode。
- 同步补全 PROTOCOL.md（hello.ack、整数、长度、偏移单位），修正原文本示例超出文本长度的选区。

已验证：
- 首次 CI 40459512395 编译失败：Kotlin 把 `"authenticated": "true"` 当布尔接受，与 Rust 行为不一致；修复后重跑。
- 手动检查 Rustfmt、锁文件、共享样例 JSON 与相对路径；未本地编译。

未验证 / 风险：
- （已由 CI 40459998825 覆盖原待验证的编译与测试项。）
- 本轮不启动服务、不认证、不注入文字；协议解析成功不代表消息已授权。
- 新 APK 发布后可由用户验证从 0.1.0-test.2 的应用内下载覆盖安装；未收到结果前保持未勾选。

## TASK-002 实施与验证记录

状态：已通过 CI 40463125888（Windows 13 项单元测试、Clippy、NSIS 打包，Android 12 项测试与 Lint 均成功）；Windows 界面上的启动/停止仍需用户实机确认。

已实现：
- `windows/src-tauri/src/server/`：WebSocket 服务控制（启动/停止/状态），绑定 `127.0.0.1:47615`；最多 8 个并发连接，15 s 握手/空闲超时，单帧与单消息上限 256 KiB。
- 认证尚未实现（TASK-003），因此所有已解析消息一律以 `AUTH_FAILED` 拒绝并关闭连接；不注入、不存储、不回显正文。
- 局域网监听（`allow_lan`）在配对与 WSS 完成前直接拒绝，不留监听端口；界面上按钮置灰并说明原因。
- 结构化日志只含事件、地址、端口与错误码，不写正文/凭据。
- React：`ServerPanel` + `useServer` 展示运行/停止、地址、端口与错误，提供启动、停止、刷新。

测试（已执行）：无认证拒绝、日志不泄露正文、端口释放与幂等停止、局域网拒绝、超限帧拒绝、连接数上限共 7 项，加协议 6 项合计 13 项 Rust 单测通过；前端 Prettier 与 tsc 通过。

本轮修复的问题（均出在测试/构建层面，非需求变更）：
- 测试用 `unwrap_err()` 要求消息类型实现 `Debug`，而协议类型故意不实现它；改为不依赖 `Debug` 的断言。
- 测试并行时争抢固定端口 47615 失败；服务改为可配置端口，测试用 0 由系统分配，正式运行仍为 47615。
- 一次拆分编辑遗漏 `port` 字段，库编译失败；已补齐。

未验证 / 风险：
- 未在 Windows 实机点击启动/停止，未用真实客户端验证端口释放。
- 服务目前无 TLS、无认证，仅限本机回环；不能作为局域网正式方案。
- 端口 47615 固定，若被占用会报错，暂不支持自定义端口。

## TASK-003a 实施与验证记录（配对协议）

状态：已完成。CI 40468263703 全绿：Android debug/release 构建、Lint、10 项单元测试；Windows 前端检查、Clippy、14 项 Rust 测试、NSIS 打包；两端已发布 test.14（`VoiceBridge-14.apk` / `VoiceBridge-14-setup.exe` + `.sig` + 清单）。

已实现：
- 新增 4 类配对消息：`pair.request`、`pair.challenge`、`pair.confirm`、`pair.result`（`status` 为 `paired/rejected/expired/mismatch/busy`）。
- 两端同时实现字段校验：nonce/token 为无填充 base64url（nonce 16–86 字符，token 32–128），`certFingerprint` 固定 `SHA256:` + 64 位小写十六进制，`expiresAt` 非负整数。
- `pair.result` 只有 `paired` 可携带 token；其它状态携带 token，或 `paired` 缺 token，均为 INVALID_MESSAGE。
- 共享样例扩展到 12 条正常消息、27 条非法消息；两端新增编码侧测试，覆盖 token 与状态绑定。
- PROTOCOL.md 增加配对章节与配对码（SAS）计算规则；PRD/ARCHITECTURE/SECURITY 同步为「mDNS 发现 + 数字比对」。

未验证 / 风险：
- 本轮只定义数据形状：配对码计算、TLS、凭据存储、设备发现均未实现。
- `pair.result` 的 `token` 为可选字段：显式 `null` 与缺省在两端都视为「无 token」，已由跨端样例覆盖。
- 当前单元测试数量：Android 10 项（协议 7 + 更新 3），Windows 14 项；TASK-002 记录中的「Android 12 项」为历史口径，与本轮无关。
- 新协议未与任何网络层接线，`server` 仍对所有消息返回 `AUTH_FAILED`。

## 用户追加：无感自动开始输入会话

用户要求电脑端不再需要点击「开始输入会话」：在电脑上点好输入框后，手机开始输入即自动同步；选择焦点属于用户在 Windows 上的操作，不由 VoiceBridge 完成。

实现：
- 手机在无活动会话时发送 `session.start`（`windowLabel = "auto"`），并缓存首个快照；服务器立即捕获当前前台窗口并广播权威 `session.start`，手机收到后补发缓存文本，因此开头字符不会丢失。
- 服务器拒绝把 VoiceBridge 自身窗口作为目标，返回 `TARGET_UNAVAILABLE`；手机显示「请先在电脑上点选输入框」。同一失败在 2 秒内不重复请求。
- 暂停/错误状态的会话可被新的输入重新武装；焦点变化与手动选区仍立即暂停。
- Windows 面板改为只读状态 + 「关闭自动同步」开关 + 「停止当前会话」；关闭自动同步后手机请求返回 `SESSION_NOT_ACTIVE`。
- 目标窗口进程号与应用自身 `std::process::id()` 比较，不依赖额外系统调用。

未验证 / 风险：
- 未编译、未运行；真机未验证「开始打字即自动开始」的时序与首字符完整性。
- 自动开始意味着：如果用户在电脑上停留在密码框或非预期窗口，手机会直接写入该窗口；这是用户选择的模式，界面需持续提示先把焦点放对。

## TASK-005~007 实施与验证记录（快照发送与差异注入）

状态：代码完成，**待 CI 验证**；端到端行为待真机测试。用户要求 005/006/007 合并为一次推送。

已实现（TASK-005 Android）：
- `BridgeClient`：用已存 token 走 `hello` 认证的长连接，指数退避重连（1 s→30 s + 抖动），15 s WebSocket ping 检测静默断线；断线即清除 sessionId 与序号。
- `SnapshotSequencer`：序号从 1 严格递增，编码失败回滚、`rejected-session` 清空。
- `SnapshotGate`：合并窗口为 `BuildConfig.SNAPSHOT_WINDOW_MS`（默认 50 ms，落在文档要求的 30–80 ms）。
- `SyncViewModel`/`SyncStatusRow`：显示未连接/已连接等待会话/正在同步/错误码；配对或解除配对即时生效。
- 编码在发送锁内完成，保证 seq 与发送顺序一致；超限时返回错误码且不发送截断帧。

已实现（TASK-006 Windows）：
- `SessionManager` 状态机：`Armed → Active`，焦点变化或选区非空 → `PausedFocus` 并拒绝后续注入（须重新开始会话）；`stop` 立即清空。
- 启动会话时捕获前台窗口（后续按用户要求改为手机发起、无需电脑点击，见「无感自动开始输入会话」）。
- 快照校验：sessionId、seq 严格递增、已绑定设备、焦点句柄一致；失败分别返回 `rejected-session` / `stale` / `paused-focus` / `injection-error`。
- `TextInjectionBackend` trait + `SendInputBackend`（Unicode 键事件，换行转 Enter，回车符跳过），可注入假后端做测试。

已实现（TASK-007 Windows）：
- `diff::delta` 用 UTF-16 code unit 求最长公共前缀，代理对不拆分；只回退变化尾部再输入新内容，**不使用 Ctrl+A 全选粘贴**。
- 服务端按 `text.snapshot` 应用并回 `text.ack`（含耗时）；会话启停通过广播下发给已认证连接。

未验证 / 风险：
- 未编译、未运行；CI 结果未知。
- 真机未验证：中文输入法增量/整句提交、emoji、换行、焦点切换暂停、停止后迟到消息丢弃。
- `SendInput` 在部分控件（管理员窗口、终端、自绘控件）可能无效，需按 TASK-011 实测。
- 无线网络抖动下的重连与序号重置只有单元测试覆盖。

## TASK-004 实施与验证记录（Android 输入法文本框）

状态：代码完成，**待 CI 验证**；真机输入法行为待用户测试。

已实现：
- `InputViewModel`：以 `TextFieldValue` 为唯一文本状态源，选择区与 composition 随状态一起保留；文本或选区变化时 `revision` 递增，供 TASK-005 生成快照。
- `InputScreen`：多行 `OutlinedTextField`，进入页面后请求焦点并调用 `LocalSoftwareKeyboardController.show()`；系统拒绝时仍可手动点按输入框；提供「清空」。
- 明文声明不录音、不调用 ASR、不点击第三方输入法麦克风（界面文案与代码注释）。
- Manifest 增加 `windowSoftInputMode="adjustResize"`，并在根布局加 `imePadding()`，避免键盘遮挡输入框（行为待真机确认）。
- 顺带修正配对页两处显示问题：已配对时不再重复出现第二行「已配对」；未配对且列表为空时才提示「未发现电脑」。

未验证 / 风险：
- 未编译、未运行；CI 结果未知。
- 未真机验证微信/百度/搜狗/豆包输入法的语音输入是否进入文本框、是否提供 composition、键盘是否自动弹出。
- 输入文本尚未发送到 Windows（TASK-005 及之后）。

## TASK-003c-2 实施与验证记录（mDNS 发现与 Android 配对界面）

状态：Windows mDNS 与 Android 客户端均通过 CI 40478283353（Rust 42 项、Android 35 项，发布 test.25）；**真机配对已成功**：用户 2026-10-27 提供截图，手机显示「已配对：JAY-DESKTOP」。负向场景（撤销、错误指纹、过期）仍只有单元测试，未真机验证。

已实现：
- Windows：`discovery.rs` 在开启局域网访问时注册 `_voicebridge._tcp.local.`，TXT 为 `v/id/name/fp`，停止或关闭局域网时注销；mDNS 失败只记日志，不阻断服务。
- Android：`DiscoveryRecord` 严格解析 TXT（拒绝缺字段、非 `v=1`、非规范指纹、非法 UTF-8）；`Discovery` 用 `NsdManager` 浏览、串行解析、按设备去重、`SERVICE_LOST` 移除。
- Android：`PairingClient` 用 `clientFor(fingerprint)` 建立 WSS，驱动既有 `PairingSession`，130 s 超时，结束即关闭 socket 与连接池。
- Android：`PairingViewModel` + `PairingScreen` 提供发现列表、点击配对、「请在电脑上点允许」、成功/失败提示、重新配对，以及主机/端口/指纹的手动连接兜底。
- `PairingSession` 的 `windowsDeviceId` 改为可空：发现路径仍严格比对；手输路径在已 pinning 的通道上采纳 challenge 里的真实 ID，并新增对应单测。

未验证 / 风险：
- Android 未编译、未运行；CI 结果未知。
- 未在真机实测 mDNS 发现、Windows 防火墙放行与完整配对流程。
- 部分路由器开启 AP 隔离时会屏蔽组播，此时只能用手动连接。
- 配对成功后尚无输入同步：本阶段只验证「能安全连上」。

## TASK-003c-1 实施与验证记录（服务端配对与本地确认）

状态：代码完成，待 CI 验证。用户在 2026-10-27 选择「本地确认」方案：自动发现 + Windows 弹窗允许，不强制核对 6 位码；配对码计算保留，留给后续高安全模式。

已实现：
- `pairing/session.rs`：单并发配对会话、120 s TTL、每 60 s 最多 5 次请求限流；两端决定都到齐才发放 token，token 只存哈希。
- 服务端消息处理：`pair.request` → `pair.challenge`（携带真实证书指纹）、`pair.confirm` → `pair.result`；配对完成后关闭连接，客户端用 token 重新 `hello`。
- 局域网监听改为显式开启：点「允许局域网访问」后绑定 `0.0.0.0` 并回报本机局域网地址；默认仍仅回环。
- 新增 Tauri 命令 `pairing_pending`、`pairing_decide`；界面新增「设备配对」区（允许/拒绝、已配对设备与撤销）和服务面板局域网状态文案。
- 本地只做格式检查：`cargo fmt --check` 与 Prettier 通过；未编译。

未验证 / 风险：
- 未编译、未运行测试；CI 结果未知。
- Android 端尚无发现与配对界面，手机无法发起真实配对（003c-2）。
- mDNS 发现尚未实现，手机列表里还看不到电脑。
- Windows 防火墙未添加规则，首次开放局域网可能触发系统提示。

## TASK-003b 实施与验证记录（传输与凭据）

状态：003b-1 已通过 CI 40469582420（Windows 30 项 Rust 测试、Clippy、NSIS；Android 10 项测试；发布 test.16）。003b-2 与 Android 配对客户端已写完，**待下一次 CI 验证**。

已实现并由 CI 验证（003b-1）：
- `identity.rs`：首次启动用 rcgen 生成自签名证书（SAN `voicebridge.local` / `localhost`），只以 DER 持久化，暴露 `SHA256:` 指纹；半写状态报 `Corrupt` 而不是悄悄轮换身份（否则已配对设备全部失效）。
- `pairing/mod.rs`：SAS 6 位码（与独立实现的向量 `873723` 交叉验证）、32 字节随机 nonce/token（无填充 base64url）、token 的 SHA-256。
- `pairing/devices.rs`：设备列表 JSON，只存 token 哈希，常量时间比较，支持撤销与 `last_seen`，原子写入；损坏文件报错而非当成空名单。
- 新增依赖 `rcgen`、`rustls(ring)`、`sha2`、`base64`、`rand`、`subtle`，`Cargo.lock` 已更新并通过 `--locked` 构建。

已完成代码，待 CI（003b-2 + Android）：
- `server/tls.rs`：rustls ring 提供者 + 自签名证书的 WSS acceptor，无明文模式。
- `server/mod.rs` 重写：TLS 握手、持久连接（不再一帧即断）、`hello` token 认证、认证前只允许 `hello`、认证后业务消息返回 `SESSION_NOT_ACTIVE`、TLS/协议失败即断、连接数与帧上限保留。
- `Status` 新增证书指纹；新增 Tauri 命令 `local_fingerprint`、`list_devices`、`revoke_device`；IPC 只暴露 `DeviceSummary`，不包含 token 哈希。
- Android：`PairingCode`（SAS/指纹/nonce）、`PairingSession`（校验指纹一致、TTL、`paired` 必须带 token、终态幂等）、`CredentialStore`（Keystore AES-GCM，失败关闭）、`PinnedTrust`（OkHttp 自定义 X509 指纹 pinning），新增 17 项 JVM 测试。

未验证 / 风险：
- 003b-2 与 Android 代码尚未经过 CI；配对 UI、mDNS 发现、LAN 监听仍未开放（TASK-003c）。
- 服务端尚未分派 session/clipboard 消息；认证后的业务消息一律 `SESSION_NOT_ACTIVE`。
- 空闲超时由 15 s 调整为 120 s，给用户核对配对码留时间；接入心跳后重新评估。
- `pair.request` 目前仍返回 `AUTH_FAILED`，配对流程会在 003c 接入服务端。
- Android 端尚未接入 UI：Keystore 存储、配对状态机已可用但无入口。

## 用户追加：Android 测试版应用内更新（随 TASK-000 交付）

用户明确要求追加此功能，优先解决真机测试每次手动寻找 APK 的问题；该追加不代表 TASK-001 至 TASK-012 已完成。

已实现代码，尚待 CI / 真机验证：
- App 启动时检查公开 GitHub Releases 固定渠道；支持检查、下载、校验和打开 Android 系统安装界面。
- 下载前用户点击确认；不后台静默下载，不静默安装。下载失败可重试，不阻断现有页面。
- SHA-256、包名、递增版本、APK 签名校验；下载限 100 MiB，清单限 16 KiB。异步任务随 ViewModel 清理取消。
- Actions 使用固定签名与 run_number 版本号，main 双端检查通过后发布测试 APK 和 update.json；PR 不发布、不访问密钥。
- `scripts/setup-android-signing.ps1` 一次性生成并配置四个签名 Secrets；不生成本地构建。用户已执行成功，已通过 `gh secret list` 确认四项名称存在（不读取密钥值）。
- 更新清单正常数据、非法 URL、版本、大小和摘要校验 JUnit 测试已在 CI 40457483697 执行：debug/release 各 3 项，均通过。
- 已验证（不编译）：actionlint 检查更新后的 workflow 通过，嵌入 Python 语法、签名脚本 PowerShell 语法、Android XML、根文档链接和 `git diff --check` 通过。
- 已通过 GitHub 只读 API 确认 `czczccc/VoiceBridge` 为公开仓库；用户已安装并登录 GitHub CLI，签名初始化和 Secrets 上传完成。

待用户真机验收：
- [x] 首次固定签名 APK 安装与启动；可显示版本、检查更新。用户于 2026-10-27 提供手机截图：欢迎页正常、版本 0.1.0-test.2，显示“暂未发现新版本”。手机型号和 Android 版本未提供；截图不证明下载或覆盖安装成功。
- [x] 发布更高版本后 App 检出，下载并覆盖安装，应用数据保留。用户于 2026-10-27 从 0.1.0-test.2 应用内检出并覆盖安装到 0.1.0-test.4（VoiceBridge-4.apk），随后截图显示“测试版本 0.1.0-test.4 / 暂未发现新版本”；数据保留和安装取消后的回退未单独验证。
- [ ] 拒绝未知来源权限、取消安装后可返回重试。
- [ ] GitHub 不可达时显示更新失败，现有应用仍可使用。
- [ ] 错误摘要、错误签名、旧版本不安装。

## 用户追加：Windows 测试版应用内更新

用户明确要求跟随 Android 的做法，避免每次从 CI 手动下载安装包；该追加不属于 TASK-001 至 TASK-012 的进度调整。

实现方式：
- 使用 Tauri 官方 updater 插件，端点固定为 `https://github.com/czczccc/VoiceBridge/releases/download/windows-testing/latest.json`，仅 HTTPS。
- 更新包必须通过 minisign 签名校验；公钥写入 `windows/src-tauri/tauri.conf.json`，私钥只存在于本机 `~/.voicebridge-signing/windows-updater.key` 与仓库 Secrets，不进入版本控制、不打印。
- 安装包为 NSIS 当前用户安装、`passive` 模式：下载完成后显示进度窗口自动安装，不弹 UAC、不需要重新运行安装向导；安装程序会先关闭应用。
- CI 每次构建将版本改写为 `0.1.1-test.<run_number>`，生成 `*-setup.exe.sig` 与 `latest.json`，先上传安装包和签名、最后上传清单，中断时旧清单仍有效。
- PR 不启用更新产物、不接触签名密钥，只做普通构建检查。

已确认（不读取密钥值）：`gh secret list` 显示 `TAURI_SIGNING_PRIVATE_KEY`、`TAURI_SIGNING_PRIVATE_KEY_PASSWORD` 已配置；公钥已写入配置并通过 `JSON.parse`、Prettier、`tsc` 检查。CI 40465399406 全绿，发布 `windows-testing` 渠道：`VoiceBridge-13-setup.exe`、`.sig`、`latest.json`（版本 0.1.1-test.13）。

实施中遇到并已修正的问题：PowerShell runner 不支持 bash heredoc（改为 `windows/scripts/*.mjs`）、NSIS 配置在 Tauri 2 需放在 `bundle.windows.nsis`、新增插件导致 `Cargo.lock` 过期且 `--locked` 拒绝构建（本地仅刷新 lock，不做编译）、以及在 CI 改写 `Cargo.toml` 版本会与 lock 冲突（改为只改 `tauri.conf.json` 版本）。

待验证：
- [ ] 首次安装带插件的版本（当前已安装的 0.1.0 无更新能力，需最后手动安装一次）。
  - 2026-10-27 用户报告已完成一次应用内更新（未提供版本号截图），下载与安装路径视为用户口头确认；安装后版本号与设置保留待补充确认。
- [ ] 发布更高版本后，界面内依次完成检查、下载、自动安装，版本号随之更新且本机设置保留。
- [ ] 断网或 GitHub 不可达时显示失败提示，应用继续可用。
- [ ] 签名不匹配或被篡改的更新包被拒绝安装。

已知风险：
- 安装包未做代码签名，下载后的安装程序仍可能触发 SmartScreen 提示。
- 私钥或密码丢失后，已安装用户将无法再接收更新，必须重新手动安装。
- 依赖公开 GitHub 可达性；不支持回滚到旧版本。

## TASK-000 实施与验证记录

状态：TASK-000 已完成。两端 CI 构建与检查通过；用户于 2026-10-27 分别提供 Android 和 Windows 安装后欢迎页截图，双端启动已确认。未进入 TASK-001。

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
- Android 和 Windows 安装后欢迎页均已由用户截图确认。应用内下载和覆盖安装待后续版本真机测试，尚无输入同步业务测试。
- Windows 安装程序暂未代码签名；Android 固定签名 Secrets 已用于成功构建和发布。构建附件和 Releases 均用于开发验证。
- 当前运行存在 Actions Node 20 / setup-java v4 迁移提示，以及 gradlew 可执行位提示（Action 自动处理）；不影响本次构建结果，后续维护处理。

假设：
- GitHub 托管 runner 可访问 npm、crates.io、Google Maven 与 Gradle 发行包服务，并可下载 NSIS。
- CI 成功仅证明构建和所执行检查通过，不代替后续输入法兼容、焦点保护和安全验收。

## 当前状态

- [x] TASK-000 仓库与构建基线（双端 CI 已通过，Android 和 Windows 实际安装启动已由用户截图确认）
- [x] TASK-001 协议模型（CI 40459998825 两端编译与测试通过）
- [x] TASK-002 Windows 服务与 UI（CI 40463125888 通过；本机回环服务与状态面板已实现，实机启动/停止待用户确认）
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
