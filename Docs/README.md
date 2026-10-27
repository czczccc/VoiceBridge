# LAN Voice Bridge（暂定名）

Android ↔ Windows 局域网实时语音输入与剪切板同步工具。

## 产品目标

把 Android 手机变成 Windows 电脑的无线输入终端。用户打开手机 App 后，系统弹出当前启用的输入法；用户使用微信输入法、百度输入法、搜狗输入法、豆包输入法等输入法自带的语音功能。输入法将文字写入 App 的文本框，App 通过局域网实时同步文本变化，Windows 端将文本更新到用户选定的输入位置。

**本产品不自行录音、不自行进行 ASR，也不要求用户更换输入法。** 语音识别由 Android 当前启用的 IME 负责。

## 文档导航

[返回仓库入口与构建说明](../README.md)。本目录保留产品与开发文档，Android 与 Windows 项目位于仓库根目录的 `android/` 和 `windows/`；CI 配置位于 `.github/workflows/build.yml`。

- `PRD.md`：产品需求、用户流程、范围与非目标。
- `ARCHITECTURE.md`：Android、Windows、网络与输入注入架构。
- `PROTOCOL.md`：WebSocket 消息协议与同步状态机。
- `SECURITY.md`：配对、网络边界、敏感文本处理与威胁模型。
- `TASKS.md`：按阶段拆分的 Claude Code 开发任务，作为实施顺序。
- `ACCEPTANCE_TESTS.md`：MVP 验收清单与边界测试。
- `CLAUDE.md`：交给 Claude Code 的项目级工作约束。

## MVP 范围

1. Android 与 Windows 通过 mDNS 自动发现，并在两端核对同一 6 位数字码后配对连接。
2. Android 文本框正常调用用户当前输入法。
3. 手机文本变化通过 WebSocket 实时发送完整快照。
4. Windows 在手机发起的输入会话中，将文本差异注入当前焦点；焦点变化时暂停。
5. 双向纯文本剪切板同步，带开关和防循环机制。
6. Windows 托盘、连接状态、日志、断线重连。
7. 通知栏操作可打开输入页并请求弹出输入法。

图片与文件剪切板、iOS、多用户协作、云端中继、自建 ASR 不属于 MVP。

## 重要设计约束

- 不承诺每种输入法都逐字输出：输入法可能只提交整句，产品同步的是文本框实际发生的每次变化。
- Windows 输入注入不能无条件安全地覆盖所有应用。MVP 采用手机发起、Windows 可停止的输入会话与焦点保护；检测到焦点变化时暂停，不盲目粘贴。
- 不通过辅助功能服务或 Root 强行控制第三方输入法。
- 默认局域网直连，不经过云端。
- 所有阶段均须构建、测试并更新 `TASKS.md`，不得一次性生成大量未验证代码。
