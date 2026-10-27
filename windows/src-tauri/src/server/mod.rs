use std::net::{Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex as StdMutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use futures_util::{SinkExt, StreamExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{oneshot, Mutex, Semaphore};
use tokio::task::JoinHandle;
use tokio_rustls::TlsAcceptor;
use tokio_tungstenite::tungstenite::protocol::WebSocketConfig;
use tokio_tungstenite::tungstenite::Message as WsMessage;

use crate::identity::Identity;
use crate::pairing::devices::{Device, DeviceStore};
use crate::protocol::{self, Body, ErrorCode, Message};

pub mod log;
pub mod tls;

pub use log::LogSink;

pub const DEFAULT_PORT: u16 = 47615;
const MAX_CONNECTIONS: usize = 8;
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(15);
/// Long enough for the user to compare and confirm a pairing code.
const IDLE_TIMEOUT: Duration = Duration::from_secs(120);
const SERVER_VERSION: &str = env!("CARGO_PKG_VERSION");

/// Everything the UI needs to render service state. No secrets, no peer addresses.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub running: bool,
    pub address: Option<String>,
    pub port: Option<u16>,
    pub lan_supported: bool,
    /// SHA-256 of the local certificate, safe to show; `None` until identity loads.
    pub fingerprint: Option<String>,
}

impl Status {
    fn stopped(fingerprint: Option<String>) -> Self {
        Self {
            running: false,
            address: None,
            port: None,
            lan_supported: false,
            fingerprint,
        }
    }
}

struct Running {
    addr: SocketAddr,
    shutdown: oneshot::Sender<()>,
    task: JoinHandle<()>,
}

/// Owns the listener and the authorization state.
///
/// The service is WSS-only: there is no plaintext mode, because a plaintext port
/// would let any device on the network read and inject text.
pub struct Server {
    state: Mutex<Option<Running>>,
    sink: Arc<dyn LogSink>,
    port: u16,
    identity: StdMutex<Option<Arc<Identity>>>,
    devices: Arc<Mutex<DeviceStore>>,
}

impl Server {
    pub fn new(sink: Arc<dyn LogSink>, devices: DeviceStore) -> Self {
        Self::with_port(sink, devices, DEFAULT_PORT)
    }

    /// Port 0 lets the OS pick a free port, which keeps parallel tests independent.
    fn with_port(sink: Arc<dyn LogSink>, devices: DeviceStore, port: u16) -> Self {
        Self {
            state: Mutex::new(None),
            sink,
            port,
            identity: StdMutex::new(None),
            devices: Arc::new(Mutex::new(devices)),
        }
    }

    /// Loads the certificate identity. Without it the service refuses to start.
    pub fn set_identity(&self, identity: Identity) {
        match self.identity.lock() {
            Ok(mut slot) => *slot = Some(Arc::new(identity)),
            Err(_) => self.sink.record("identity-lock-failed"),
        }
    }

    pub fn fingerprint(&self) -> Option<String> {
        self.identity
            .lock()
            .ok()
            .and_then(|slot| slot.as_ref().map(|identity| identity.fingerprint().to_string()))
    }

    pub async fn status(&self) -> Status {
        let fingerprint = self.fingerprint();
        match self.state.lock().await.as_ref() {
            Some(running) => running_status(running.addr, fingerprint),
            None => Status::stopped(fingerprint),
        }
    }

    pub async fn list_devices(&self) -> Vec<Device> {
        self.devices.lock().await.list().to_vec()
    }

    pub async fn authorize_device(
        &self,
        device_id: &str,
        name: &str,
        token: &str,
        now: u64,
    ) -> Result<(), String> {
        self.devices
            .lock()
            .await
            .authorize(device_id, name, token, now)
            .map_err(|_| "无法保存设备授权".to_string())
    }

    pub async fn revoke_device(&self, device_id: &str) -> Result<bool, String> {
        self.devices
            .lock()
            .await
            .revoke(device_id)
            .map_err(|_| "无法保存设备授权".to_string())
    }

    /// Loopback only. LAN exposure stays closed until discovery and the pairing
    /// confirmation UI exist (TASK-003c); an unreachable pairing flow must not
    /// leave an open port.
    pub async fn start(&self, allow_lan: bool) -> Result<Status, String> {
        let identity = self.identity.lock().ok().and_then(|slot| slot.clone());
        let mut state = self.state.lock().await;
        if let Some(running) = state.as_ref() {
            return Ok(running_status(running.addr, self.fingerprint()));
        }
        if allow_lan {
            return Err(
                "局域网监听需要先完成设备配对界面与自动发现，当前仅开放本机回环地址".to_string(),
            );
        }
        let identity = identity.ok_or("本机证书尚未生成，无法启动加密服务")?;
        let acceptor = tls::acceptor(&identity)?;

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
        let task = tokio::spawn(accept_loop(
            listener,
            receiver,
            self.sink.clone(),
            acceptor,
            self.devices.clone(),
        ));
        self.sink.record(&format!("started addr={addr}"));
        *state = Some(Running {
            addr,
            shutdown,
            task,
        });
        Ok(running_status(addr, self.fingerprint()))
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
        Status::stopped(self.fingerprint())
    }
}

fn running_status(addr: SocketAddr, fingerprint: Option<String>) -> Status {
    Status {
        running: true,
        address: Some(addr.ip().to_string()),
        port: Some(addr.port()),
        lan_supported: false,
        fingerprint,
    }
}

async fn accept_loop(
    listener: TcpListener,
    shutdown: oneshot::Receiver<()>,
    sink: Arc<dyn LogSink>,
    acceptor: TlsAcceptor,
    devices: Arc<Mutex<DeviceStore>>,
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
                    let acceptor = acceptor.clone();
                    let devices = devices.clone();
                    tokio::spawn(async move {
                        serve_connection(stream, acceptor, sink, devices).await;
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

/// What to do after one inbound frame.
enum Decision {
    /// Accept the handshake and keep the connection.
    Acknowledged,
    /// Answer with an error but keep the connection usable.
    Refused(ErrorCode),
    /// Answer with an error and close the connection.
    Closed(ErrorCode),
}

async fn serve_connection(
    stream: TcpStream,
    acceptor: TlsAcceptor,
    sink: Arc<dyn LogSink>,
    devices: Arc<Mutex<DeviceStore>>,
) {
    let config = WebSocketConfig::default()
        .max_message_size(Some(protocol::MAX_MESSAGE_BYTES))
        .max_frame_size(Some(protocol::MAX_MESSAGE_BYTES));

    let tls = match tokio::time::timeout(HANDSHAKE_TIMEOUT, acceptor.accept(stream)).await {
        Ok(Ok(tls)) => tls,
        _ => {
            sink.record("tls-handshake-failed");
            return;
        }
    };
    let mut socket = match tokio::time::timeout(
        HANDSHAKE_TIMEOUT,
        tokio_tungstenite::accept_async_with_config(tls, Some(config)),
    )
    .await
    {
        Ok(Ok(socket)) => socket,
        _ => {
            sink.record("handshake-failed");
            return;
        }
    };

    // Device id of the authenticated peer. `None` means only `hello` is allowed.
    let mut authenticated: Option<String> = None;

    loop {
        let incoming = tokio::time::timeout(IDLE_TIMEOUT, socket.next()).await;
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

        let message = match protocol::decode(&payload) {
            Ok(message) => message,
            Err(code) => {
                sink.record(&format!("frame-rejected code={code:?}"));
                let _ = socket.send(WsMessage::Binary(error_frame(code).into())).await;
                break;
            }
        };

        let decision = decide(&devices, &mut authenticated, &message).await;
        sink.record(&format!(
            "frame-handled type={} result={}",
            kind(&message.body),
            label(&decision)
        ));
        match decision {
            Decision::Acknowledged => {
                let Some(frame) = acknowledgement() else {
                    break;
                };
                if socket.send(WsMessage::Binary(frame.into())).await.is_err() {
                    break;
                }
            }
            Decision::Refused(code) => {
                if socket
                    .send(WsMessage::Binary(error_frame(code).into()))
                    .await
                    .is_err()
                {
                    break;
                }
            }
            Decision::Closed(code) => {
                let _ = socket.send(WsMessage::Binary(error_frame(code).into())).await;
                break;
            }
        }
    }
    let _ = socket.close(None).await;
}

async fn decide(
    devices: &Mutex<DeviceStore>,
    authenticated: &mut Option<String>,
    message: &Message,
) -> Decision {
    match &message.body {
        Body::Hello {
            device_id, auth, ..
        } => {
            if authenticated.is_some() {
                return Decision::Refused(ErrorCode::InvalidMessage);
            }
            if devices.lock().await.is_authorized(device_id, &auth.token) {
                *authenticated = Some(device_id.clone());
                Decision::Acknowledged
            } else {
                Decision::Closed(ErrorCode::AuthFailed)
            }
        }
        // Authentication is a precondition for everything else. Session and
        // clipboard handling arrive with their own tasks.
        _ => match authenticated {
            Some(_) => Decision::Refused(ErrorCode::SessionNotActive),
            None => Decision::Closed(ErrorCode::AuthFailed),
        },
    }
}

/// Stable names for logging. Never echoes payload text.
fn kind(body: &Body) -> &'static str {
    match body {
        Body::Hello { .. } => "hello",
        Body::HelloAck { .. } => "hello.ack",
        Body::SessionStart { .. } => "session.start",
        Body::TextSnapshot { .. } => "text.snapshot",
        Body::TextAck { .. } => "text.ack",
        Body::SessionStop { .. } => "session.stop",
        Body::ClipboardText { .. } => "clipboard.text",
        Body::Error { .. } => "error",
        Body::PairRequest { .. } => "pair.request",
        Body::PairChallenge { .. } => "pair.challenge",
        Body::PairConfirm { .. } => "pair.confirm",
        Body::PairResult { .. } => "pair.result",
    }
}

fn label(decision: &Decision) -> String {
    match decision {
        Decision::Acknowledged => "ack".to_string(),
        Decision::Refused(code) | Decision::Closed(code) => format!("{code:?}"),
    }
}

fn acknowledgement() -> Option<Vec<u8>> {
    encode_body(Body::HelloAck {
        server_version: SERVER_VERSION.to_string(),
        connection_id: format!("connection-{}", next_counter()),
        capabilities: vec![
            "pairing".to_string(),
            "text.snapshot".to_string(),
            "clipboard.text".to_string(),
        ],
        authenticated: true,
    })
}

fn error_frame(code: ErrorCode) -> Vec<u8> {
    encode_body(Body::Error {
        code,
        message: describe(code).to_string(),
    })
    .unwrap_or_default()
}

fn encode_body(body: Body) -> Option<Vec<u8>> {
    let message = Message {
        v: protocol::VERSION,
        message_id: format!("server-{}-{}", now_millis(), next_counter()),
        sent_at: now_millis().min(protocol::MAX_INTEGER),
        body,
    };
    protocol::encode(&message).ok()
}

fn next_counter() -> u64 {
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    COUNTER.fetch_add(1, Ordering::Relaxed)
}

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

/// Fixed, non-sensitive text only. Never echo client input.
fn describe(code: ErrorCode) -> &'static str {
    match code {
        ErrorCode::AuthFailed => "认证失败，设备未获得授权",
        ErrorCode::MessageTooLarge => "消息超过长度上限",
        ErrorCode::UnsupportedProtocol => "不支持的协议版本",
        ErrorCode::SessionNotActive => "当前没有可由该设备使用的输入会话",
        _ => "消息无效",
    }
}

#[cfg(test)]
mod tests;
