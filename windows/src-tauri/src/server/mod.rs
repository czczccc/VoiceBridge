use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex as StdMutex};
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{oneshot, Mutex, Semaphore};
use tokio::task::JoinHandle;
use tokio_rustls::TlsAcceptor;
use tokio_tungstenite::tungstenite::protocol::WebSocketConfig;
use tokio_tungstenite::tungstenite::Message as WsMessage;

use crate::identity::Identity;
use crate::pairing::devices::{Device, DeviceStore};
use crate::pairing::session::{
    now_millis, ClientConfirm, PairOutcome, PairingManager, PendingPairing, PAIRING_TTL_MS,
};
use crate::protocol::{self, Body, ErrorCode, Message, PairStatus};

pub mod log;
pub mod tls;

pub use log::LogSink;

pub const DEFAULT_PORT: u16 = 47615;
const MAX_CONNECTIONS: usize = 8;
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(15);
/// Long enough for the user to approve a pairing request.
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
    lan: bool,
    shutdown: oneshot::Sender<()>,
    task: JoinHandle<()>,
}

/// Owns the listener, the local identity and the authorization state.
///
/// The service is WSS-only: a plaintext port would let any device on the network
/// read and inject text.
pub struct Server {
    state: Mutex<Option<Running>>,
    sink: Arc<dyn LogSink>,
    port: u16,
    identity: StdMutex<Option<Arc<Identity>>>,
    pairing: StdMutex<Option<Arc<PairingManager>>>,
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
            pairing: StdMutex::new(None),
            devices: Arc::new(Mutex::new(devices)),
        }
    }

    /// Loads the certificate identity and derives the local pairing device id from it.
    /// Without an identity the service refuses to start.
    pub fn set_identity(&self, identity: Identity) {
        let fingerprint = identity.fingerprint().to_string();
        let device_id = local_device_id(&fingerprint);
        let name = local_device_name();
        let manager = PairingManager::new(self.devices.clone(), device_id, name);
        match self.pairing.lock() {
            Ok(mut slot) => *slot = Some(Arc::new(manager)),
            Err(_) => self.sink.record("pairing-lock-failed"),
        }
        match self.identity.lock() {
            Ok(mut slot) => *slot = Some(Arc::new(identity)),
            Err(_) => self.sink.record("identity-lock-failed"),
        }
    }

    pub fn fingerprint(&self) -> Option<String> {
        self.identity.lock().ok().and_then(|slot| {
            slot.as_ref()
                .map(|identity| identity.fingerprint().to_string())
        })
    }

    fn pairing(&self) -> Option<Arc<PairingManager>> {
        self.pairing.lock().ok().and_then(|slot| slot.clone())
    }

    pub async fn status(&self) -> Status {
        let fingerprint = self.fingerprint();
        match self.state.lock().await.as_ref() {
            Some(running) => running_status(running.addr, running.lan, fingerprint),
            None => Status::stopped(fingerprint),
        }
    }

    pub async fn list_devices(&self) -> Vec<Device> {
        self.devices.lock().await.list().to_vec()
    }

    pub async fn revoke_device(&self, device_id: &str) -> Result<bool, String> {
        self.devices
            .lock()
            .await
            .revoke(device_id)
            .map_err(|_| "无法保存设备授权".to_string())
    }

    /// The request the local user must approve, if any.
    pub fn pending_pairing(&self) -> Option<PendingPairing> {
        self.pairing()?.pending()
    }

    /// Records the local Allow/Deny decision. Idempotent when nothing is pending.
    pub async fn decide_pairing(&self, accepted: bool) -> Result<(), String> {
        let Some(pairing) = self.pairing() else {
            return Err("配对功能尚未就绪".to_string());
        };
        pairing
            .local_decision(accepted)
            .await
            .map_err(|_| "无法保存配对决定".to_string())
    }

    /// Starts the WSS listener. `allow_lan` is an explicit, per-session opt-in:
    /// without it the service only listens on loopback.
    pub async fn start(&self, allow_lan: bool) -> Result<Status, String> {
        let identity = self.identity.lock().ok().and_then(|slot| slot.clone());
        let mut state = self.state.lock().await;
        if let Some(running) = state.as_ref() {
            return Ok(running_status(
                running.addr,
                running.lan,
                self.fingerprint(),
            ));
        }
        let identity = identity.ok_or("本机证书尚未生成，无法启动加密服务")?;
        let acceptor = tls::acceptor(&identity)?;

        let bind_ip = if allow_lan {
            Ipv4Addr::UNSPECIFIED
        } else {
            Ipv4Addr::LOCALHOST
        };
        let listener = TcpListener::bind((bind_ip, self.port))
            .await
            .map_err(|error| {
                self.sink.record(&format!("bind-failed port={}", self.port));
                format!("无法监听端口（{}）", error.kind())
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
            self.pairing(),
            identity.fingerprint().to_string(),
        ));
        self.sink
            .record(&format!("started bind={} lan={allow_lan}", addr.ip()));
        let reported = if allow_lan {
            SocketAddr::new(
                primary_lan_ip()
                    .map(IpAddr::V4)
                    .unwrap_or(IpAddr::V4(Ipv4Addr::UNSPECIFIED)),
                addr.port(),
            )
        } else {
            addr
        };
        *state = Some(Running {
            addr: reported,
            lan: allow_lan,
            shutdown,
            task,
        });
        Ok(running_status(reported, allow_lan, self.fingerprint()))
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

fn running_status(addr: SocketAddr, lan: bool, fingerprint: Option<String>) -> Status {
    Status {
        running: true,
        address: Some(addr.ip().to_string()),
        port: Some(addr.port()),
        lan_supported: lan,
        fingerprint,
    }
}

/// Stable id derived from the certificate fingerprint, so it survives restarts
/// without another file to keep in sync.
fn local_device_id(fingerprint: &str) -> String {
    let hex = fingerprint.strip_prefix("SHA256:").unwrap_or(fingerprint);
    let prefix: String = hex.chars().take(16).collect();
    format!("windows-{prefix}")
}

fn local_device_name() -> String {
    match std::env::var("COMPUTERNAME") {
        Ok(name) if !name.trim().is_empty() => name,
        _ => "Windows".to_string(),
    }
}

/// Best-effort private address for display. UDP connect sends no packets; it only
/// asks the routing table which local address would be used.
fn primary_lan_ip() -> Option<Ipv4Addr> {
    let socket = std::net::UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).ok()?;
    socket.connect("192.0.2.1:9").ok()?;
    match socket.local_addr().ok()?.ip() {
        IpAddr::V4(ip) if !ip.is_loopback() && !ip.is_unspecified() => Some(ip),
        _ => None,
    }
}

async fn accept_loop(
    listener: TcpListener,
    shutdown: oneshot::Receiver<()>,
    sink: Arc<dyn LogSink>,
    acceptor: TlsAcceptor,
    devices: Arc<Mutex<DeviceStore>>,
    pairing: Option<Arc<PairingManager>>,
    fingerprint: String,
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
                    let pairing = pairing.clone();
                    let fingerprint = fingerprint.clone();
                    tokio::spawn(async move {
                        serve_connection(stream, acceptor, sink, devices, pairing, fingerprint).await;
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

async fn serve_connection(
    stream: TcpStream,
    acceptor: TlsAcceptor,
    sink: Arc<dyn LogSink>,
    devices: Arc<Mutex<DeviceStore>>,
    pairing: Option<Arc<PairingManager>>,
    fingerprint: String,
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

    // Device id of the authenticated peer. `None` means only pairing and `hello` are allowed.
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
                let _ = socket
                    .send(WsMessage::Binary(error_frame(code).into()))
                    .await;
                break;
            }
        };
        sink.record(&format!("frame-handled type={}", kind(&message.body)));

        let closing = match &message.body {
            Body::Hello {
                device_id, auth, ..
            } => {
                if authenticated.is_some() {
                    send(&mut socket, error_frame(ErrorCode::InvalidMessage)).await
                } else if authorize(&devices, device_id, &auth.token).await {
                    authenticated = Some(device_id.clone());
                    send(&mut socket, acknowledgement()).await
                } else {
                    sink.record("auth-failed");
                    let _ = socket
                        .send(WsMessage::Binary(error_frame(ErrorCode::AuthFailed).into()))
                        .await;
                    true
                }
            }
            Body::PairRequest {
                device_id,
                device_name,
                ..
            } => {
                if authenticated.is_some() {
                    send(&mut socket, error_frame(ErrorCode::InvalidMessage)).await
                } else if let Some(pairing) = pairing.as_ref() {
                    match pairing.begin(device_id, device_name) {
                        Ok(challenge) => {
                            let frame = encode_body(Body::PairChallenge {
                                pairing_id: challenge.pairing_id,
                                server_device_id: pairing.server_device_id().to_string(),
                                server_name: pairing.server_name().to_string(),
                                server_nonce: challenge.server_nonce,
                                cert_fingerprint: fingerprint.clone(),
                                expires_at: challenge.expires_at,
                            });
                            send(&mut socket, frame.unwrap_or_default()).await
                        }
                        Err(_) => {
                            let _ = socket
                                .send(WsMessage::Binary(
                                    error_frame(ErrorCode::RateLimited).into(),
                                ))
                                .await;
                            true
                        }
                    }
                } else {
                    let _ = socket
                        .send(WsMessage::Binary(error_frame(ErrorCode::AuthFailed).into()))
                        .await;
                    true
                }
            }
            Body::PairConfirm {
                pairing_id,
                confirmed,
            } => {
                if authenticated.is_some() {
                    send(&mut socket, error_frame(ErrorCode::InvalidMessage)).await
                } else if let Some(pairing) = pairing.as_ref() {
                    let outcome = match pairing.client_confirm(pairing_id, *confirmed).await {
                        ClientConfirm::Complete(outcome) => Some(outcome),
                        ClientConfirm::Waiting(receiver) => {
                            PairingManager::wait(receiver, Duration::from_millis(PAIRING_TTL_MS))
                                .await
                        }
                        ClientConfirm::Stale => None,
                    };
                    let outcome = outcome.unwrap_or(PairOutcome::Expired);
                    if matches!(outcome, PairOutcome::Expired) {
                        pairing.expire(pairing_id);
                    }
                    let frame = encode_body(pair_result_frame(
                        pairing_id,
                        outcome,
                        pairing.server_device_id(),
                        pairing.server_name(),
                    ));
                    // The client reconnects and authenticates with the new token.
                    let _ = socket
                        .send(WsMessage::Binary(frame.unwrap_or_default().into()))
                        .await;
                    true
                } else {
                    let _ = socket
                        .send(WsMessage::Binary(error_frame(ErrorCode::AuthFailed).into()))
                        .await;
                    true
                }
            }
            _ => {
                if authenticated.is_some() {
                    send(&mut socket, error_frame(ErrorCode::SessionNotActive)).await
                } else {
                    sink.record("auth-required");
                    let _ = socket
                        .send(WsMessage::Binary(error_frame(ErrorCode::AuthFailed).into()))
                        .await;
                    true
                }
            }
        };
        if closing {
            break;
        }
    }
    let _ = socket.close(None).await;
}

async fn authorize(devices: &Mutex<DeviceStore>, device_id: &str, token: &str) -> bool {
    let mut store = devices.lock().await;
    let authorized = store.is_authorized(device_id, token);
    if authorized {
        // Best effort: a failure to persist "last seen" must not reject a valid token.
        let _ = store.touch(device_id, now_millis());
    }
    authorized
}

async fn send(
    socket: &mut tokio_tungstenite::WebSocketStream<tokio_rustls::server::TlsStream<TcpStream>>,
    frame: Vec<u8>,
) -> bool {
    socket.send(WsMessage::Binary(frame.into())).await.is_err()
}

fn pair_result_frame(
    pairing_id: &str,
    outcome: PairOutcome,
    server_device_id: &str,
    server_name: &str,
) -> Body {
    match outcome {
        PairOutcome::Paired { token } => Body::PairResult {
            pairing_id: pairing_id.to_string(),
            status: PairStatus::Paired,
            token: Some(token),
            server_device_id: server_device_id.to_string(),
            server_name: server_name.to_string(),
        },
        PairOutcome::Rejected => Body::PairResult {
            pairing_id: pairing_id.to_string(),
            status: PairStatus::Rejected,
            token: None,
            server_device_id: server_device_id.to_string(),
            server_name: server_name.to_string(),
        },
        PairOutcome::Expired => Body::PairResult {
            pairing_id: pairing_id.to_string(),
            status: PairStatus::Expired,
            token: None,
            server_device_id: server_device_id.to_string(),
            server_name: server_name.to_string(),
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

fn acknowledgement() -> Vec<u8> {
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
    .unwrap_or_default()
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

/// Fixed, non-sensitive text only. Never echo client input.
fn describe(code: ErrorCode) -> &'static str {
    match code {
        ErrorCode::AuthFailed => "认证失败，设备未获得授权",
        ErrorCode::MessageTooLarge => "消息超过长度上限",
        ErrorCode::UnsupportedProtocol => "不支持的协议版本",
        ErrorCode::SessionNotActive => "当前没有可由该设备使用的输入会话",
        ErrorCode::RateLimited => "配对请求过于频繁或已有配对正在进行",
        _ => "消息无效",
    }
}

#[cfg(test)]
impl Server {
    /// Seeds an authorized device without going through the pairing flow.
    pub(crate) async fn authorize_device(
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
}

#[cfg(test)]
mod tests;
