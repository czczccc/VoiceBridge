# LAN Voice Bridge 协议草案

## 1. 约定

- 传输：WebSocket over TLS（WSS），局域网直连。
- 编码：UTF-8 JSON。
- 协议版本：`1`。
- 每条消息包含 `v`、`type`、`messageId`、`sentAt`。
- 任何消息均有长度上限：单条完整 UTF-8 JSON（包含字段名、转义和空白）最多 262144 字节（256 KiB），收发均校验；文本和剪切板共用此限制，文件传输另行设计。
- 必需字段不能缺失或为 null；字段类型严格校验，不将字符串转换为数字或布尔值。整数使用 JSON 非负整数写法（不接受小数、指数形式或引号），最大 9007199254740991；seq 最小 1，sentAt 和 latencyMs 最小 0。
- messageId、sessionId、deviceId、connectionId、eventId、originDeviceId 为非空标识符（UTF-8 最多 256 字节），不强制 UUID 格式；版本名称、windowLabel、reason 和 capability 同样非空且最多 256 字节。token 非空且最多 4096 字节，error.message 非空且最多 1024 字节；capabilities 最多 32 项。
- 配对 nonce 与 `pair.result` 的 token 使用无填充 base64url（`A-Z`、`a-z`、`0-9`、`-`、`_`）：nonce 16–86 字符，token 32–128 字符。`certFingerprint` 固定为 `SHA256:` 加 64 位小写十六进制、不带冒号，界面显示时再插入冒号。`expiresAt` 与 `sentAt` 一样是非负整数（毫秒）。
- 未知消息类型、未知枚举值、无效 UTF-8、结构不符一律 INVALID_MESSAGE。完整帧超限优先 MESSAGE_TOO_LARGE；可解析的非负整数版本不是 1 时优先 UNSUPPORTED_PROTOCOL。
- 消息模型不自动生成包含正文或令牌的调试表示；解码失败只提供静态错误码。
- 消息中的文本只用于内存处理；不得默认持久化正文。

## 2. 设备身份与配对

Windows 生成本机证书与私钥并持久保存。配对流程：

1. Windows 通过 mDNS 广播 `_voicebridge._tcp.local`，TXT 记录包含设备 ID、名称、端口、协议版本与证书 SHA-256 指纹。
2. Android 用 `NsdManager` 浏览发现，用户点选目标电脑后建立 TLS 连接。
3. 两端按 3.7 的消息序列交换 nonce，并各自计算 6 位配对码；用户在两端核对一致后确认。
4. Windows 发放长期 token，只保存其 SHA-256；Android 定位到 Keystore。

mDNS 广播内容（包括指纹）**未认证**，只用于定位候选设备；信任只在用户核对配对码后建立。无法接收 mDNS 的网络允许手动输入地址，后续流程不变。

一次性 nonce 短时有效、用后立即失效；配对会话有限流与尝试次数上限，避免在 Windows 上反复弹窗。不要把长期访问令牌放进可长期复用或截图传播的内容。

## 3. 消息类型

认证完成前，服务器只接受 `pair.request`（见 3.7），其它任何类型一律返回 `AUTH_FAILED` 并关闭连接。

### 3.1 `hello`

客户端握手：
```json
{
  "v": 1,
  "type": "hello",
  "messageId": "uuid",
  "sentAt": 1780000000000,
  "deviceId": "android-device-id",
  "clientVersion": "0.1.0",
  "auth": {
    "token": "redacted"
  }
}
```

服务器响应 `hello.ack`，认证失败后关闭连接。以下字段均必需，authenticated 必须为布尔值：
```json
{
  "v": 1,
  "type": "hello.ack",
  "messageId": "uuid",
  "sentAt": 1780000000000,
  "serverVersion": "0.1.0",
  "connectionId": "connection-1",
  "capabilities": ["text.snapshot", "clipboard.text"],
  "authenticated": true
}
```

### 3.2 `session.start`

Windows 本地用户启动输入会话后，服务器创建 session：
```json
{
  "v": 1,
  "type": "session.start",
  "messageId": "uuid",
  "sentAt": 1780000000000,
  "sessionId": "uuid",
  "target": {
    "windowLabel": "Notepad",
    "focusPolicy": "pause-on-focus-change"
  }
}
```

Android 收到后显示会话状态。MVP 不要求 Android 自动开始语音；用户仍需点击输入法自己的麦克风。

### 3.3 `text.snapshot`

Android 文本状态变化：
```json
{
  "v": 1,
  "type": "text.snapshot",
  "messageId": "uuid",
  "sentAt": 1780000000000,
  "sessionId": "uuid",
  "seq": 42,
  "text": "我想开发一个局域网语音输入工具",
  "selection": {
    "start": 15,
    "end": 15
  },
  "composition": null
}
```

约束：
- `seq` 在同一 session 内严格递增。
- Windows 只接受当前 session 且 `seq` 大于已应用序号的快照。
- `text` 是当前完整文本状态，而非增量。
- selection/composition 是可选字段，允许缺失或 null；接收端不得假定所有 IME 都提供。
- 范围的 start/end 均为必需的非负整数，单位为 UTF-16 code unit，范围为 0..文本 UTF-16 长度（含端点）；不能落在代理对中间。selection 允许反向选区（start > end）；composition 要求 start <= end。
- 空文本有效，用于清空会话文本。seq 的结构检查不代替运行时会话和已应用序号检查；后者在后续会话管理任务实现。
- 如文本超过上限，返回错误，不静默截断。

响应 `text.ack`：
```json
{
  "v": 1,
  "type": "text.ack",
  "messageId": "uuid",
  "sentAt": 1780000000000,
  "sessionId": "uuid",
  "seq": 42,
  "status": "applied",
  "latencyMs": 34
}
```

`status`：`applied`、`stale`、`paused-focus`、`rejected-session`、`injection-error`。

### 3.4 `session.stop`

任一端请求停止：
```json
{
  "v": 1,
  "type": "session.stop",
  "messageId": "uuid",
  "sentAt": 1780000000000,
  "sessionId": "uuid",
  "reason": "user"
}
```

停止后所有旧 session 的 `text.snapshot` 必须被拒绝。网络重连不自动复活旧 session。

### 3.5 `clipboard.text`

纯文本剪切板事件：
```json
{
  "v": 1,
  "type": "clipboard.text",
  "messageId": "uuid",
  "sentAt": 1780000000000,
  "eventId": "uuid",
  "originDeviceId": "android-device-id",
  "direction": "android-to-windows",
  "text": "要同步的文本"
}
```

防循环规则：
- 记录最近一段时间内已处理的 `eventId`。
- 接收端写入剪切板后，将来源标记为远端同步事件；若平台不提供可靠来源标记，则通过内容哈希 + 短期事件窗口去重。
- 相同内容的用户再次主动复制仍应允许同步，不能仅用文本相等永久去重。

### 3.6 `error`

```json
{
  "v": 1,
  "type": "error",
  "messageId": "uuid",
  "sentAt": 1780000000000,
  "code": "FOCUS_CHANGED",
  "message": "输入焦点发生变化，已暂停同步"
}
```

`message` 不包含敏感正文、密钥或系统路径。

### 3.7 配对消息

配对复用同一条 WSS 连接，不额外开端口。两端先通过 mDNS（`_voicebridge._tcp.local`）相互发现，用户在 Android 列表中点选电脑后开始以下四步。

`pair.request`（Android → Windows，允许的唯一未认证消息）：
```json
{
  "v": 1,
  "type": "pair.request",
  "messageId": "uuid",
  "sentAt": 1780000000000,
  "deviceId": "android-device-id",
  "deviceName": "Pixel 7",
  "clientVersion": "0.1.0",
  "clientNonce": "base64url-32-bytes"
}
```

`pair.challenge`（Windows → Android）：
```json
{
  "v": 1,
  "type": "pair.challenge",
  "messageId": "uuid",
  "sentAt": 1780000000000,
  "pairingId": "pairing-1",
  "serverDeviceId": "windows-device-id",
  "serverName": "DESKTOP-ABC",
  "serverNonce": "base64url-32-bytes",
  "certFingerprint": "SHA256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "expiresAt": 1780000120000
}
```

`pair.confirm`（Android → Windows，两端用户都核对配对码后）：
```json
{
  "v": 1,
  "type": "pair.confirm",
  "messageId": "uuid",
  "sentAt": 1780000000000,
  "pairingId": "pairing-1",
  "confirmed": true
}
```

`pair.result`（Windows → Android）：
```json
{
  "v": 1,
  "type": "pair.result",
  "messageId": "uuid",
  "sentAt": 1780000000000,
  "pairingId": "pairing-1",
  "status": "paired",
  "token": "base64url-32-bytes",
  "serverDeviceId": "windows-device-id",
  "serverName": "DESKTOP-ABC"
}
```

`status`：`paired`、`rejected`、`expired`、`mismatch`、`busy`。只有 `paired` 携带 token，其它状态必须不带 token；`paired` 缺少 token 视为 INVALID_MESSAGE。

配对码（SAS，数字比对）**不在网络中传输**，两端各自计算：

```text
code = SHA-256("voicebridge-sas-v1" ‖ 客户端实际观察到的服务器证书指纹 ‖ clientNonce ‖ serverNonce)
       前 4 字节按无符号大端取模 1_000_000，左侧补零为 6 位
```

- 客户端必须先用 TLS 握手中看到的证书指纹核对 `certFingerprint`，不一致立即中止，不显示配对码。
- 中间人必须建立两条 TLS 连接，两端证书指纹不同，算出的配对码也不同，用户核对即可发现。
- 配对码只用于人工核对，不作为凭据；配对码不一致时用户拒绝，服务器返回 `mismatch`。

配对规则：
- 当前采用「本地确认」模式：配对码按上式仍可计算，但界面不强制显示与核对，是否放行由 Windows 本地用户点击「允许/拒绝」决定；`pair.result` 不因未核对配对码而失败。设置中的高安全模式可要求人工核对配对码，届时码不一致应返回 `mismatch`。
- 配对会话 TTL 120 秒；超过 `expiresAt` 的 `pair.confirm` 返回 `expired`。
- 同一时间只允许一个配对会话；并发请求返回 `busy`。
- Windows 本地用户与 Android 用户都确认后才生成 token。
- token 只在该响应中传输一次；Windows 只保存 `SHA-256(token)`，Android 存入 Android Keystore。
- 撤销设备后其 token 立即失效，后续 `hello` 返回 `AUTH_FAILED`。
- 配对请求限流，避免在 Windows 上反复弹窗骚扰。

## 4. 错误码

- `AUTH_FAILED`
- `PAIRING_EXPIRED`
- `UNSUPPORTED_PROTOCOL`
- `MESSAGE_TOO_LARGE`
- `INVALID_MESSAGE`
- `SESSION_NOT_ACTIVE`
- `STALE_SEQUENCE`
- `FOCUS_CHANGED`
- `TARGET_UNAVAILABLE`
- `INJECTION_FAILED`
- `CLIPBOARD_DISABLED`
- `RATE_LIMITED`

## 5. 状态机

### Windows 输入会话
`Idle → Armed → Active → PausedFocus → Active`（仅用户重新确认目标后）
`Active → Stopping → Idle`
`Active → Error → Idle`

- `Armed`：用户明确启动会话，已捕获目标窗口。
- `Active`：焦点校验通过，允许文本注入。
- `PausedFocus`：焦点变化，停止所有注入。
- `Stopping`：拒绝新快照，清理会话状态。
- 断线后必须回到 `Idle`，不自动恢复旧 session。

### Android 连接
`Unpaired → Pairing → Connected → Reconnecting → Connected`
`Connected → Disconnected`
输入会话状态与网络连接状态分开管理。

## 6. 版本兼容

- 每条消息有 `v`。
- 不认识的必需字段或协议主版本必须拒绝。
- 可选字段允许忽略。
- 协议变化时增加测试样例，更新此文档与实现。

## 7. 共享测试样例与实现边界

- `protocol/fixtures/messages.json`：12 类消息的正常样例（含 hello.ack 与 4 类配对消息），Kotlin 与 Rust 共用；样例中的 token、nonce 与指纹均为虚构值。
- `protocol/fixtures/invalid.json`：跨端共用的非法字段、版本、序号、枚举和 UTF-16 边界样例及预期错误码。
- 两端另测所有必需字段缺失/null、完整帧大小边界、UTF-8 字节限制、往返编解码、未知可选字段、空文本。
- TASK-001 仅定义 Idle/Armed/Active/PausedFocus/Stopping/Error 及 Android 连接状态，不执行状态转换，不实现认证、传输、旧序号去重或注入。认证状态不由解析 hello 或 hello.ack 自动建立。
- TASK-003a 只定义配对消息、字段规则与共享样例；mDNS 发现、证书生成、配对码计算、token 存储与设备撤销在后续批次实现。
