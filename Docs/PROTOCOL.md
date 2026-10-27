# LAN Voice Bridge 协议草案

## 1. 约定

- 传输：WebSocket over TLS（WSS），局域网直连。
- 编码：UTF-8 JSON。
- 协议版本：`1`。
- 每条消息包含 `v`、`type`、`messageId`、`sentAt`。
- 任何消息均有长度上限；初始限制 256 KiB，文件传输另行设计。
- 消息中的文本只用于内存处理；不得默认持久化正文。

## 2. 设备身份与配对

建议 Windows 生成本机证书与密钥，配对时展示二维码，其中包含：
- Windows LAN 地址与端口。
- 一次性 pairing nonce。
- 服务器证书 SHA-256 指纹。
- 协议版本。

Android 扫码后验证证书指纹，再用一次性 nonce 完成配对。长期凭据存入 Android Keystore；Windows 存储设备授权信息时避免明文保存共享秘密。一次性 nonce 使用后立即失效。

二维码/配对码应短时有效，最多尝试次数有限。不要把长期访问令牌放进可长期截图复用的二维码。

## 3. 消息类型

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

服务器响应 `hello.ack`，包含 `serverVersion`、`connectionId`、能力列表与认证状态。认证失败后关闭连接。

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
    "start": 18,
    "end": 18
  },
  "composition": null
}
```

约束：
- `seq` 在同一 session 内严格递增。
- Windows 只接受当前 session 且 `seq` 大于已应用序号的快照。
- `text` 是当前完整文本状态，而非增量。
- selection/composition 是可选字段；接收端不得假定所有 IME 都提供。
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
