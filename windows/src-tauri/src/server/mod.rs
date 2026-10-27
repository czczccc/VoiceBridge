use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex as StdMutex};
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{broadcast, mpsc, oneshot, Mutex, Semaphore};
use tokio::task::JoinHandle;
use tokio_rustls::TlsAcceptor;
use tokio_tungstenite::tungstenite::protocol::WebSocketConfig;
use tokio_tungstenite::tungstenite::Message as WsMessage;

use crate::discovery::{self, Advertiser};
use crate::identity::Identity;
use crate::pairing::devices::{Device, DeviceStore};
use crate::pairing::session::{
    now_millis, ClientConfirm, PairOutcome, PairingManager, PendingPairing, PAIRING_TTL_MS,
};
use crate::protocol::{self, Body, ErrorCode, FocusPolicy, Message, PairStatus, Target};
use crate::session::{SessionInfo, SessionManager};

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
    advert: StdMutex<Option<Advertiser>>,
    devices: Arc<Mutex<DeviceStore>>,
    sessions: Arc<SessionManager>,
    outbound: broadcast::Sender<Vec<u8>>,
    /// When off, the phone cannot arm a session; the local user can still stop one.
    auto_sync: Arc<AtomicBool>,
}

impl Server {
    pub fn new(sink: Arc<dyn LogSink>, devices: DeviceStore) -> Self {
        Self::with_port(sink, devices, DEFAULT_PORT)
    }

    /// Port 0 lets the OS pick a free port, which keeps parallel tests independent.
    fn with_port(sink: Arc<dyn LogSink>, devices: DeviceStore, port: u16) -> Self {
        let (backend, focus) = crate::session::defaults();
        let (outbound, _) = broadcast::channel(32);
        Self {
            state: Mutex::new(None),
            sink,
            port,
            identity: StdMutex::new(None),
            pairing: StdMutex::new(None),
            advert: StdMutex::new(None),
            devices: Arc::new(Mutex::new(devices)),
            sessions: Arc::new(SessionManager::new(backend, focus)),
            outbound,
            auto_sync: Arc::new(AtomicBool::new(true)),
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

    pub fn session_info(&self) -> Option<SessionInfo> {
        self.sessions.info()
    }

    /// Whether the phone may arm a session without a local click.
    pub fn auto_sync(&self) -> bool {
        self.auto_sync.load(Ordering::Relaxed)
    }

    /// Turns auto-arming on or off. Turning it off also ends any running session.
    pub fn set_auto_sync(&self, enabled: bool) -> bool {
        self.auto_sync.store(enabled, Ordering::Relaxed);
        if !enabled {
            self.stop_session();
        }
        enabled
    }

    pub fn stop_session(&self) -> Option<String> {
        let id = self.sessions.stop();
        if let Some(stopped) = id.clone() {
            self.broadcast(Body::SessionStop {
                session_id: stopped,
                reason: "user".to_string(),
            });
        }
        id
    }

    fn broadcast(&self, body: Body) {
        if let Some(frame) = encode_body(body) {
            let _ = self.outbound.send(frame);
        }
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
        let context = Arc::new(ConnectionContext {
            sink: self.sink.clone(),
            devices: self.devices.clone(),
            pairing: self.pairing(),
            sessions: self.sessions.clone(),
            outbound: self.outbound.clone(),
            auto_sync: self.auto_sync.clone(),
            fingerprint: identity.fingerprint().to_string(),
        });
        let task = tokio::spawn(accept_loop(listener, receiver, acceptor, context));
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
        self.stop_advertising();
        if allow_lan {
            self.advertise(addr.port());
        }
        *state = Some(Running {
            addr: reported,
            lan: allow_lan,
            shutdown,
            task,
        });
        Ok(running_status(reported, allow_lan, self.fingerprint()))
    }

    pub async fn stop(&self) -> Status {
        self.stop_advertising();
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

impl Server {
    /// Best effort: a machine without a usable LAN address still serves loopback.
    fn advertise(&self, port: u16) {
        let Some(fingerprint) = self.fingerprint() else {
            return;
        };
        let Some(address) = primary_lan_ip() else {
            self.sink.record("mdns-skipped reason=no-lan-address");
            return;
        };
        let device_id = local_device_id(&fingerprint);
        let properties = discovery::properties(&device_id, &local_device_name(), &fingerprint);
        let instance = format!("VoiceBridge-{device_id}");
        match Advertiser::start(&instance, port, address, properties) {
            Ok(advertiser) => {
                if let Ok(mut slot) = self.advert.lock() {
                    *slot = Some(advertiser);
                }
                self.sink.record("mdns-advertising");
            }
            Err(error) => self.sink.record(&format!("mdns-failed reason={error}")),
        }
    }

    fn stop_advertising(&self) {
        if let Ok(mut slot) = self.advert.lock() {
            *slot = None;
        }
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

struct ConnectionContext {
    sink: Arc<dyn LogSink>,
    devices: Arc<Mutex<DeviceStore>>,
    pairing: Option<Arc<PairingManager>>,
    sessions: Arc<SessionManager>,
    outbound: broadcast::Sender<Vec<u8>>,
    auto_sync: Arc<AtomicBool>,
    fingerprint: String,
}

async fn accept_loop(
    listener: TcpListener,
    shutdown: oneshot::Receiver<()>,
    acceptor: TlsAcceptor,
    context: Arc<ConnectionContext>,
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
                            context
                                .sink
                                .record("connection-rejected reason=too-many-connections");
                            continue;
                        }
                    };
                    let acceptor = acceptor.clone();
                    let context = context.clone();
                    tokio::spawn(async move {
                        serve_connection(stream, acceptor, context).await;
                        drop(permit);
                    });
                }
                Err(error) => {
                    context
                        .sink
                        .record(&format!("accept-failed kind={:?}", error.kind()));
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
    context: Arc<ConnectionContext>,
) {
    let config = WebSocketConfig::default()
        .max_message_size(Some(protocol::MAX_MESSAGE_BYTES))
        .max_frame_size(Some(protocol::MAX_MESSAGE_BYTES));

    let tls = match tokio::time::timeout(HANDSHAKE_TIMEOUT, acceptor.accept(stream)).await {
        Ok(Ok(tls)) => tls,
        _ => {
            context.sink.record("tls-handshake-failed");
            return;
        }
    };
    let socket = match tokio::time::timeout(
        HANDSHAKE_TIMEOUT,
        tokio_tungstenite::accept_async_with_config(tls, Some(config)),
    )
    .await
    {
        Ok(Ok(socket)) => socket,
        _ => {
            context.sink.record("handshake-failed");
            return;
        }
    };
    let (mut sink, mut incoming) = socket.split();

    // Replies and broadcasts both leave through this channel, so the read loop never
    // borrows the socket for writing.
    let (frames, mut queued) = mpsc::channel::<Vec<u8>>(32);
    let writer = tokio::spawn(async move {
        while let Some(frame) = queued.recv().await {
            if sink.send(WsMessage::Binary(frame.into())).await.is_err() {
                break;
            }
        }
        let _ = sink.close().await;
    });

    let authenticated = Arc::new(AtomicBool::new(false));
    let mut broadcasts = context.outbound.subscribe();
    let forward = {
        let authenticated = authenticated.clone();
        let frames = frames.clone();
        tokio::spawn(async move {
            loop {
                match broadcasts.recv().await {
                    Ok(frame) => {
                        if !authenticated.load(Ordering::Relaxed) {
                            continue;
                        }
                        if frames.send(frame).await.is_err() {
                            break;
                        }
                    }
                    Err(broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(broadcast::error::RecvError::Closed) => break,
                }
            }
        })
    };

    let mut device: Option<String> = None;
    loop {
        let inbound = tokio::time::timeout(IDLE_TIMEOUT, incoming.next()).await;
        let payload = match inbound {
            Ok(Some(Ok(WsMessage::Binary(bytes)))) => bytes.to_vec(),
            Ok(Some(Ok(WsMessage::Text(text)))) => text.as_str().as_bytes().to_vec(),
            Ok(Some(Ok(WsMessage::Close(_)))) | Ok(None) => break,
            Ok(Some(Ok(_))) => continue,
            Ok(Some(Err(_))) => {
                context.sink.record("read-failed");
                break;
            }
            Err(_) => {
                context.sink.record("idle-timeout");
                break;
            }
        };

        let message = match protocol::decode(&payload) {
            Ok(message) => message,
            Err(code) => {
                context
                    .sink
                    .record(&format!("frame-rejected code={code:?}"));
                let _ = frames.send(error_frame(code)).await;
                break;
            }
        };
        context
            .sink
            .record(&format!("frame-handled type={}", kind(&message.body)));

        let (reply, close) = match &message.body {
            Body::Hello {
                device_id, auth, ..
            } => {
                if device.is_some() {
                    (Some(error_frame(ErrorCode::InvalidMessage)), false)
                } else if authorize(&context.devices, device_id, &auth.token).await {
                    device = Some(device_id.clone());
                    authenticated.store(true, Ordering::Relaxed);
                    (Some(acknowledgement()), false)
                } else {
                    context.sink.record("auth-failed");
                    (Some(error_frame(ErrorCode::AuthFailed)), true)
                }
            }
            Body::PairRequest {
                device_id,
                device_name,
                ..
            } => pairing_reply(&context, device.is_some(), device_id, device_name).await,
            Body::PairConfirm {
                pairing_id,
                confirmed,
            } => pairing_confirm(&context, device.is_some(), pairing_id, *confirmed).await,
            Body::TextSnapshot {
                session_id,
                seq,
                text,
                selection,
                ..
            } => match device.as_deref() {
                None => {
                    context.sink.record("auth-required");
                    (Some(error_frame(ErrorCode::AuthFailed)), true)
                }
                Some(device_id) => {
                    let started = now_millis();
                    let status = context
                        .sessions
                        .apply(device_id, session_id, *seq, text, *selection);
                    let latency = now_millis()
                        .saturating_sub(started)
                        .min(protocol::MAX_INTEGER);
                    (
                        encode_body(Body::TextAck {
                            session_id: session_id.clone(),
                            seq: *seq,
                            status,
                            latency_ms: latency,
                        }),
                        false,
                    )
                }
            },
            Body::SessionStart { .. } => match device.as_deref() {
                None => {
                    context.sink.record("auth-required");
                    (Some(error_frame(ErrorCode::AuthFailed)), true)
                }
                Some(_) => match arm_auto(&context.sessions, &context.auto_sync, &context.outbound)
                {
                    Ok(_) => (None, false),
                    Err(code) => {
                        context
                            .sink
                            .record(&format!("auto-arm-refused code={code:?}"));
                        (Some(error_frame(code)), false)
                    }
                },
            },
            Body::SessionStop { reason, .. } => {
                if device.is_none() {
                    (Some(error_frame(ErrorCode::AuthFailed)), true)
                } else if let Some(stopped) = context.sessions.stop() {
                    if let Some(frame) = encode_body(Body::SessionStop {
                        session_id: stopped,
                        reason: reason.clone(),
                    }) {
                        let _ = context.outbound.send(frame);
                    }
                    (None, false)
                } else {
                    (None, false)
                }
            }
            _ => {
                if device.is_some() {
                    (Some(error_frame(ErrorCode::SessionNotActive)), false)
                } else {
                    context.sink.record("auth-required");
                    (Some(error_frame(ErrorCode::AuthFailed)), true)
                }
            }
        };

        if let Some(frame) = reply {
            if frames.send(frame).await.is_err() {
                break;
            }
        }
        if close {
            break;
        }
    }

    forward.abort();
    drop(frames);
    let _ = tokio::time::timeout(Duration::from_secs(5), writer).await;

    // The session belongs to the device that was writing; once it disconnects the
    // session cannot continue.
    if let Some(device_id) = device {
        if context.sessions.bound_device().as_deref() == Some(device_id.as_str()) {
            context.sessions.stop();
            context
                .sink
                .record("session-stopped reason=peer-disconnected");
        }
    }
}

/// Arms a session at whatever window is in front right now.
///
/// This is the "no click on the PC" path: the phone starts a session when the user begins
/// typing, and the local focus choice is whatever window they selected with the mouse. The
/// VoiceBridge window itself is refused, so a forgotten target cannot swallow the text.
fn arm_auto(
    sessions: &SessionManager,
    auto_sync: &AtomicBool,
    outbound: &broadcast::Sender<Vec<u8>>,
) -> Result<SessionInfo, ErrorCode> {
    if !auto_sync.load(Ordering::Relaxed) {
        return Err(ErrorCode::SessionNotActive);
    }
    // Re-use a live session, but start over after a pause or an error: the user asked to
    // type again and the target has to be recaptured.
    if let Some(info) = sessions.info() {
        if info.state == "armed" || info.state == "active" {
            return Ok(info);
        }
        sessions.stop();
    }
    let Some(target) = sessions.foreground() else {
        return Err(ErrorCode::TargetUnavailable);
    };
    if target.process_id == std::process::id() {
        return Err(ErrorCode::TargetUnavailable);
    }
    let info = sessions.start(target);
    if let Some(frame) = encode_body(Body::SessionStart {
        session_id: info.session_id.clone(),
        target: Target {
            window_label: info.target_label.clone(),
            focus_policy: FocusPolicy::PauseOnFocusChange,
        },
    }) {
        let _ = outbound.send(frame);
    }
    Ok(info)
}

async fn pairing_reply(
    context: &ConnectionContext,
    authenticated: bool,
    device_id: &str,
    device_name: &str,
) -> (Option<Vec<u8>>, bool) {
    if authenticated {
        return (Some(error_frame(ErrorCode::InvalidMessage)), false);
    }
    let Some(pairing) = context.pairing.as_ref() else {
        return (Some(error_frame(ErrorCode::AuthFailed)), true);
    };
    match pairing.begin(device_id, device_name) {
        Ok(challenge) => {
            let frame = encode_body(Body::PairChallenge {
                pairing_id: challenge.pairing_id,
                server_device_id: pairing.server_device_id().to_string(),
                server_name: pairing.server_name().to_string(),
                server_nonce: challenge.server_nonce,
                cert_fingerprint: context.fingerprint.clone(),
                expires_at: challenge.expires_at,
            });
            (Some(frame.unwrap_or_default()), false)
        }
        Err(_) => (Some(error_frame(ErrorCode::RateLimited)), true),
    }
}

async fn pairing_confirm(
    context: &ConnectionContext,
    authenticated: bool,
    pairing_id: &str,
    confirmed: bool,
) -> (Option<Vec<u8>>, bool) {
    if authenticated {
        return (Some(error_frame(ErrorCode::InvalidMessage)), false);
    }
    let Some(pairing) = context.pairing.as_ref() else {
        return (Some(error_frame(ErrorCode::AuthFailed)), true);
    };
    let outcome = match pairing.client_confirm(pairing_id, confirmed).await {
        ClientConfirm::Complete(outcome) => Some(outcome),
        ClientConfirm::Waiting(receiver) => {
            PairingManager::wait(receiver, Duration::from_millis(PAIRING_TTL_MS)).await
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
    (Some(frame.unwrap_or_default()), true)
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
        ErrorCode::TargetUnavailable => "请先在电脑上点选要输入的位置",
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
