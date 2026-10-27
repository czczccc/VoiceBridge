use std::net::{Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use futures_util::{SinkExt, StreamExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{oneshot, Mutex, Semaphore};
use tokio::task::JoinHandle;
use tokio_tungstenite::tungstenite::protocol::WebSocketConfig;
use tokio_tungstenite::tungstenite::Message as WsMessage;

use crate::protocol::{self, Body, ErrorCode, Message};

pub mod log;

pub use log::LogSink;

pub const DEFAULT_PORT: u16 = 47615;
const MAX_CONNECTIONS: usize = 8;
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(15);

/// Everything the UI needs to render service state. No secrets, no addresses of peers.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub running: bool,
    pub address: Option<String>,
    pub port: Option<u16>,
    pub lan_supported: bool,
}

impl Status {
    fn stopped() -> Self {
        Self {
            running: false,
            address: None,
            port: None,
            lan_supported: false,
        }
    }
}

struct Running {
    addr: SocketAddr,
    shutdown: oneshot::Sender<()>,
    task: JoinHandle<()>,
}

/// Owns the listener. Starting is idempotent; stopping always releases the port.
pub struct Server {
    state: Mutex<Option<Running>>,
    sink: Arc<dyn LogSink>,
}

impl Server {
    pub fn new(sink: Arc<dyn LogSink>) -> Self {
        Self {
            state: Mutex::new(None),
            sink,
        }
    }

    pub async fn status(&self) -> Status {
        match self.state.lock().await.as_ref() {
            Some(running) => running_status(running.addr),
            None => Status::stopped(),
        }
    }

    /// Loopback only. LAN listening stays closed until pairing and WSS exist (TASK-003),
    /// because an unauthenticated plaintext port must never accept remote input control.
    pub async fn start(&self, allow_lan: bool) -> Result<Status, String> {
        let mut state = self.state.lock().await;
        if let Some(running) = state.as_ref() {
            return Ok(running_status(running.addr));
        }
        if allow_lan {
            return Err(
                "局域网监听需要先完成设备配对与加密认证，当前仅开放本机回环地址".to_string(),
            );
        }

        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, self.port))
            .await
            .map_err(|error| {
                self.sink.record(&format!("bind-failed port={}", self.port));
                format!("无法监听本机端口（{}）", error.kind())
            })?;
        let addr = listener
            .local_addr()
            .map_err(|_| "无法获取监听地址".to_string())?;

        let (shutdown, receiver) = oneshot::channel();
        let task = tokio::spawn(accept_loop(listener, receiver, self.sink.clone()));
        self.sink.record(&format!("started addr={addr}"));
        *state = Some(Running {
            addr,
            shutdown,
            task,
        });
        Ok(running_status(addr))
    }

    pub async fn stop(&self) -> Status {
        let running = self.state.lock().await.take();
        match running {
            Some(running) => {
                let _ = running.shutdown.send(());
                // Awaiting the task guarantees the listener is dropped before we report stopped.
                let _ = running.task.await;
                self.sink.record("stopped");
            }
            None => self.sink.record("stop-ignored reason=not-running"),
        }
        Status::stopped()
    }
}

fn running_status(addr: SocketAddr) -> Status {
    Status {
        running: true,
        address: Some(addr.ip().to_string()),
        port: Some(addr.port()),
        lan_supported: false,
    }
}

async fn accept_loop(
    listener: TcpListener,
    shutdown: oneshot::Receiver<()>,
    sink: Arc<dyn LogSink>,
) {
    let permits = Arc::new(Semaphore::new(MAX_CONNECTIONS));
    tokio::pin!(shutdown);
    loop {
        tokio::select! {
            _ = &mut shutdown => break,
            accepted = listener.accept() => match accepted {
                Ok((stream, _peer)) => {
                    let permit = match permits.clone().try_acquire_owned() {
                        Ok(permit) => permit,
                        Err(_) => {
                            sink.record("connection-rejected reason=too-many-connections");
                            continue;
                        }
                    };
                    let sink = sink.clone();
                    tokio::spawn(async move {
                        serve_connection(stream, sink).await;
                        drop(permit);
                    });
                }
                Err(error) => {
                    sink.record(&format!("accept-failed kind={:?}", error.kind()));
                    tokio::time::sleep(Duration::from_millis(50)).await;
                }
            }
        }
    }
    drop(listener);
}

async fn serve_connection(stream: TcpStream, sink: Arc<dyn LogSink>) {
    let config = WebSocketConfig::default()
        .max_message_size(Some(protocol::MAX_MESSAGE_BYTES))
        .max_frame_size(Some(protocol::MAX_MESSAGE_BYTES));
    let mut socket = match tokio_tungstenite::accept_async_with_config(stream, Some(config)).await {
        Ok(socket) => socket,
        Err(_) => {
            sink.record("handshake-failed");
            return;
        }
    };

    loop {
        let incoming = tokio::time::timeout(HANDSHAKE_TIMEOUT, socket.next()).await;
        let payload = match incoming {
            Ok(Some(Ok(WsMessage::Binary(bytes)))) => bytes.to_vec(),
            Ok(Some(Ok(WsMessage::Text(text)))) => text.as_str().as_bytes().to_vec(),
            Ok(Some(Ok(WsMessage::Close(_)))) | Ok(None) => break,
            Ok(Some(Ok(_))) => continue,
            Ok(Some(Err(_))) => {
                sink.record("read-failed");
                break;
            }
            Err(_) => {
                sink.record("idle-timeout");
                break;
            }
        };

        let reply = match protocol::decode(&payload) {
            Ok(message) => reply_for(&message),
            Err(code) => error_frame(code),
        };
        sink.record(&format!("frame-handled code={:?}", reply.0));
        if let Some(frame) = reply.1 {
            let _ = socket.send(WsMessage::Binary(frame.into())).await;
        }
        // Every accepted frame ends the connection in this task: no message type is
        // authorized yet, so keeping the socket open would only invite a retry storm.
        break;
    }
    let _ = socket.close(None).await;
}

/// No message type is authorized before authentication exists, so every decoded frame
/// is refused with AUTH_FAILED and the socket is closed. Nothing is injected or stored.
fn reply_for(_message: &Message) -> (ErrorCode, Option<Vec<u8>>) {
    // No message type may be authorized before authentication exists (TASK-003), so every
    // well-formed frame is refused the same way. Nothing is injected, stored, or logged.
    let code = ErrorCode::AuthFailed;
    (code, error_frame(code).1)
}

fn error_frame(code: ErrorCode) -> (ErrorCode, Option<Vec<u8>>) {
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64;
    let message = Message {
        v: protocol::VERSION,
        message_id: format!("server-{now}-{}", COUNTER.fetch_add(1, Ordering::Relaxed)),
        sent_at: now.min(protocol::MAX_INTEGER),
        body: Body::Error {
            code,
            message: describe(code).to_string(),
        },
    };
    match protocol::encode(&message) {
        Ok(bytes) => (code, Some(bytes)),
        Err(_) => (code, None),
    }
}

/// Fixed, non-sensitive text only. Never echo client input.
fn describe(code: ErrorCode) -> &'static str {
    match code {
        ErrorCode::AuthFailed => "认证功能尚未实现，已拒绝连接",
        ErrorCode::MessageTooLarge => "消息超过长度上限",
        ErrorCode::UnsupportedProtocol => "不支持的协议版本",
        _ => "消息无效",
    }
}

#[cfg(test)]
mod tests;
