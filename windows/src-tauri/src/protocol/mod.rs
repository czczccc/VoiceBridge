use serde::{Deserialize, Serialize};

pub const VERSION: u32 = 1;
pub const MAX_MESSAGE_BYTES: usize = 256 * 1024;
pub const MAX_INTEGER: u64 = 9_007_199_254_740_991;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ErrorCode {
    AuthFailed,
    PairingExpired,
    UnsupportedProtocol,
    MessageTooLarge,
    InvalidMessage,
    SessionNotActive,
    StaleSequence,
    FocusChanged,
    TargetUnavailable,
    InjectionFailed,
    ClipboardDisabled,
    RateLimited,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SessionState {
    Idle,
    Armed,
    Active,
    PausedFocus,
    Stopping,
    Error,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ConnectionState {
    Unpaired,
    Pairing,
    Connected,
    Reconnecting,
    Disconnected,
}

// Intentionally no Debug: messages contain private text and credentials.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Message {
    pub v: u32,
    pub message_id: String,
    pub sent_at: u64,
    #[serde(flatten)]
    pub body: Body,
}

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum Body {
    #[serde(rename = "hello", rename_all = "camelCase")]
    Hello {
        device_id: String,
        client_version: String,
        auth: Auth,
    },
    #[serde(rename = "hello.ack", rename_all = "camelCase")]
    HelloAck {
        server_version: String,
        connection_id: String,
        capabilities: Vec<String>,
        authenticated: bool,
    },
    #[serde(rename = "session.start", rename_all = "camelCase")]
    SessionStart { session_id: String, target: Target },
    #[serde(rename = "text.snapshot", rename_all = "camelCase")]
    TextSnapshot {
        session_id: String,
        seq: u64,
        text: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        selection: Option<TextRange>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        composition: Option<TextRange>,
    },
    #[serde(rename = "text.ack", rename_all = "camelCase")]
    TextAck {
        session_id: String,
        seq: u64,
        status: AckStatus,
        latency_ms: u64,
    },
    #[serde(rename = "session.stop", rename_all = "camelCase")]
    SessionStop { session_id: String, reason: String },
    #[serde(rename = "clipboard.text", rename_all = "camelCase")]
    ClipboardText {
        event_id: String,
        origin_device_id: String,
        direction: Direction,
        text: String,
    },
    #[serde(rename = "error")]
    Error { code: ErrorCode, message: String },
}

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Auth {
    pub token: String,
}

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Target {
    pub window_label: String,
    pub focus_policy: FocusPolicy,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum FocusPolicy {
    #[serde(rename = "pause-on-focus-change")]
    PauseOnFocusChange,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum AckStatus {
    #[serde(rename = "applied")]
    Applied,
    #[serde(rename = "stale")]
    Stale,
    #[serde(rename = "paused-focus")]
    PausedFocus,
    #[serde(rename = "rejected-session")]
    RejectedSession,
    #[serde(rename = "injection-error")]
    InjectionError,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Direction {
    #[serde(rename = "android-to-windows")]
    AndroidToWindows,
    #[serde(rename = "windows-to-android")]
    WindowsToAndroid,
}

// Offsets are UTF-16 code units; never allow the middle of a surrogate pair.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct TextRange {
    pub start: u64,
    pub end: u64,
}

fn valid(value: bool) -> Result<(), ErrorCode> {
    if value {
        Ok(())
    } else {
        Err(ErrorCode::InvalidMessage)
    }
}

fn short(value: &str) -> Result<(), ErrorCode> {
    valid(!value.trim().is_empty() && value.len() <= 256)
}

fn sequence(seq: u64) -> Result<(), ErrorCode> {
    valid((1..=MAX_INTEGER).contains(&seq))
}

fn range(text: &str, range: &TextRange, ordered: bool) -> Result<(), ErrorCode> {
    let boundary = |offset: u64| {
        if offset == 0 {
            return true;
        }
        let mut position = 0;
        text.chars().any(|character| {
            position += character.len_utf16() as u64;
            position == offset
        })
    };
    valid(boundary(range.start) && boundary(range.end) && (!ordered || range.start <= range.end))
}

impl Message {
    pub fn validate(&self) -> Result<(), ErrorCode> {
        if self.v != VERSION {
            return Err(ErrorCode::UnsupportedProtocol);
        }
        short(&self.message_id)?;
        valid(self.sent_at <= MAX_INTEGER)?;
        match &self.body {
            Body::Hello {
                device_id,
                client_version,
                auth,
            } => {
                short(device_id)?;
                short(client_version)?;
                valid(!auth.token.trim().is_empty() && auth.token.len() <= 4096)?;
            }
            Body::HelloAck {
                server_version,
                connection_id,
                capabilities,
                ..
            } => {
                short(server_version)?;
                short(connection_id)?;
                valid(capabilities.len() <= 32)?;
                for capability in capabilities {
                    short(capability)?;
                }
            }
            Body::SessionStart { session_id, target } => {
                short(session_id)?;
                short(&target.window_label)?;
            }
            Body::TextSnapshot {
                session_id,
                seq,
                text,
                selection,
                composition,
            } => {
                short(session_id)?;
                sequence(*seq)?;
                if let Some(selection) = selection {
                    range(text, selection, false)?;
                }
                if let Some(composition) = composition {
                    range(text, composition, true)?;
                }
            }
            Body::TextAck {
                session_id,
                seq,
                latency_ms,
                ..
            } => {
                short(session_id)?;
                sequence(*seq)?;
                valid(*latency_ms <= MAX_INTEGER)?;
            }
            Body::SessionStop { session_id, reason } => {
                short(session_id)?;
                short(reason)?;
            }
            Body::ClipboardText {
                event_id,
                origin_device_id,
                ..
            } => {
                short(event_id)?;
                short(origin_device_id)?;
            }
            Body::Error { message, .. } => {
                valid(!message.trim().is_empty() && message.len() <= 1024)?;
            }
        }
        Ok(())
    }
}

pub fn decode(bytes: &[u8]) -> Result<Message, ErrorCode> {
    if bytes.len() > MAX_MESSAGE_BYTES {
        return Err(ErrorCode::MessageTooLarge);
    }
    // Validate version before the type-specific payload so unsupported versions have a stable error.
    let envelope: serde_json::Value =
        serde_json::from_slice(bytes).map_err(|_| ErrorCode::InvalidMessage)?;
    match envelope.get("v").and_then(|v| v.as_u64()) {
        Some(1) => (),
        Some(_) => return Err(ErrorCode::UnsupportedProtocol),
        None => return Err(ErrorCode::InvalidMessage),
    }
    let message: Message =
        serde_json::from_value(envelope).map_err(|_| ErrorCode::InvalidMessage)?;
    message.validate()?;
    Ok(message)
}

pub fn encode(message: &Message) -> Result<Vec<u8>, ErrorCode> {
    message.validate()?;
    let bytes = serde_json::to_vec(message).map_err(|_| ErrorCode::InvalidMessage)?;
    if bytes.len() > MAX_MESSAGE_BYTES {
        return Err(ErrorCode::MessageTooLarge);
    }
    Ok(bytes)
}

#[cfg(test)]
mod tests;
