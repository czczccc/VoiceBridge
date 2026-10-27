use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use rustls::pki_types::{CertificateDer, ServerName};
use tokio::net::TcpStream;
use tokio_rustls::client::TlsStream;
use tokio_rustls::TlsConnector;
use tokio_tungstenite::tungstenite::Message as WsMessage;

use super::log::MemorySink;
use super::{Server, MAX_CONNECTIONS};
use crate::identity::Identity;
use crate::pairing::devices::DeviceStore;
use crate::protocol::{self, Body, ErrorCode, Message};

type Client = tokio_tungstenite::WebSocketStream<TlsStream<TcpStream>>;

struct Fixture {
    server: Server,
    certificate: CertificateDer<'static>,
    sink: Arc<MemorySink>,
    directory: PathBuf,
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.directory);
    }
}

fn fixture() -> Fixture {
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let directory = std::env::temp_dir().join(format!(
        "voicebridge-server-{}-{}",
        std::process::id(),
        COUNTER.fetch_add(1, Ordering::Relaxed)
    ));
    let identity = Identity::load_or_create(&directory).expect("identity");
    let certificate = identity.certificate();
    let devices = DeviceStore::load(directory.join("devices.json")).expect("devices");
    let sink = Arc::new(MemorySink::default());
    let server = Server::with_port(sink.clone(), devices, 0);
    server.set_identity(identity);
    Fixture {
        server,
        certificate,
        sink,
        directory,
    }
}

impl Fixture {
    async fn start(&self) -> String {
        let status = self.server.start(false).await.expect("start");
        format!(
            "{}:{}",
            status.address.expect("address"),
            status.port.expect("port")
        )
    }

    /// TLS only, so the connection holds a slot without completing the WebSocket handshake.
    async fn try_tls(&self, address: &str) -> std::io::Result<TlsStream<TcpStream>> {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let mut roots = rustls::RootCertStore::empty();
        roots
            .add(self.certificate.clone())
            .expect("certificate is a trust anchor");
        let config = rustls::ClientConfig::builder()
            .with_root_certificates(roots)
            .with_no_client_auth();
        let connector = TlsConnector::from(Arc::new(config));
        let name = ServerName::try_from("voicebridge.local").expect("server name");
        connector
            .connect(name, TcpStream::connect(address).await?)
            .await
    }

    async fn tls(&self, address: &str) -> TlsStream<TcpStream> {
        self.try_tls(address).await.expect("tls")
    }

    async fn connect(&self, address: &str) -> Client {
        let tls = self.tls(address).await;
        let (socket, _) = tokio_tungstenite::client_async("wss://voicebridge.local/", tls)
            .await
            .expect("websocket");
        socket
    }
}

fn token() -> String {
    "A".repeat(43)
}

fn hello(device_id: &str, token: &str) -> Vec<u8> {
    protocol::encode(&Message {
        v: protocol::VERSION,
        message_id: "m-hello".to_string(),
        sent_at: 1,
        body: Body::Hello {
            device_id: device_id.to_string(),
            client_version: "0.1.0".to_string(),
            auth: protocol::Auth {
                token: token.to_string(),
            },
        },
    })
    .expect("hello encodes")
}

fn text_snapshot(text: &str) -> Vec<u8> {
    protocol::encode(&Message {
        v: protocol::VERSION,
        message_id: "m-snapshot".to_string(),
        sent_at: 1,
        body: Body::TextSnapshot {
            session_id: "s-test".to_string(),
            seq: 1,
            text: text.to_string(),
            selection: None,
            composition: None,
        },
    })
    .expect("snapshot encodes")
}

async fn next_body(socket: &mut Client) -> Body {
    let frame = tokio::time::timeout(Duration::from_secs(5), socket.next())
        .await
        .expect("server replied in time")
        .expect("server sent a frame")
        .expect("frame is valid");
    let bytes = match frame {
        WsMessage::Binary(bytes) => bytes.to_vec(),
        WsMessage::Text(text) => text.as_str().as_bytes().to_vec(),
        other => panic!("unexpected frame: {other:?}"),
    };
    protocol::decode(&bytes).expect("reply decodes").body
}

async fn next_error(socket: &mut Client) -> ErrorCode {
    match next_body(socket).await {
        Body::Error { code, .. } => code,
        other => panic!("unexpected body kind: {:?}", std::mem::discriminant(&other)),
    }
}

/// The server must not keep a refused connection open.
async fn assert_closed(socket: &mut Client) {
    loop {
        match tokio::time::timeout(Duration::from_secs(5), socket.next()).await {
            Ok(Some(Ok(WsMessage::Close(_)))) | Ok(None) => return,
            Ok(Some(Ok(_))) => continue,
            Ok(Some(Err(_))) => return,
            Err(_) => panic!("server kept the connection open"),
        }
    }
}

#[tokio::test]
async fn plaintext_clients_are_refused() {
    let fixture = fixture();
    let address = fixture.start().await;
    let attempt = tokio::time::timeout(
        Duration::from_secs(5),
        tokio_tungstenite::connect_async(format!("ws://{address}")),
    )
    .await
    .expect("attempt finished");
    assert!(attempt.is_err(), "a plaintext handshake must not succeed");
    assert!(fixture
        .sink
        .lines()
        .iter()
        .any(|line| line == "tls-handshake-failed"));
    fixture.server.stop().await;
}

#[tokio::test]
async fn unauthorized_frames_are_refused_without_logging_bodies() {
    let fixture = fixture();
    let address = fixture.start().await;

    let secret = "这是一段不应出现在日志里的正文";
    let wrong = "still-not-a-valid-token";
    for payload in [text_snapshot(secret), hello("android-1", wrong)] {
        let mut socket = fixture.connect(&address).await;
        socket
            .send(WsMessage::Binary(payload.into()))
            .await
            .expect("send");
        assert_eq!(next_error(&mut socket).await, ErrorCode::AuthFailed);
        assert_closed(&mut socket).await;
    }

    let lines = fixture.sink.lines();
    for line in &lines {
        assert!(!line.contains(secret), "log leaked body: {line}");
        assert!(!line.contains(wrong), "log leaked credential: {line}");
    }
    assert!(lines.iter().any(|line| line.contains("AuthFailed")));
    fixture.server.stop().await;
}

#[tokio::test]
async fn authorized_hello_keeps_the_connection_open() {
    let fixture = fixture();
    fixture
        .server
        .authorize_device("android-1", "Pixel 7", &token(), 1)
        .await
        .expect("authorize");
    let address = fixture.start().await;

    let mut socket = fixture.connect(&address).await;
    socket
        .send(WsMessage::Binary(hello("android-1", &token()).into()))
        .await
        .expect("send hello");
    match next_body(&mut socket).await {
        Body::HelloAck { authenticated, .. } => assert!(authenticated),
        other => panic!("unexpected body kind: {:?}", std::mem::discriminant(&other)),
    }

    // A second frame proves the server no longer closes after one message.
    socket
        .send(WsMessage::Binary(text_snapshot("正文").into()))
        .await
        .expect("send snapshot");
    assert_eq!(next_error(&mut socket).await, ErrorCode::SessionNotActive);

    // Re-authenticating on the same connection is a protocol error, not a crash.
    socket
        .send(WsMessage::Binary(hello("android-1", &token()).into()))
        .await
        .expect("send hello again");
    assert_eq!(next_error(&mut socket).await, ErrorCode::InvalidMessage);
    fixture.server.stop().await;
}

#[tokio::test]
async fn revoked_devices_lose_access_immediately() {
    let fixture = fixture();
    fixture
        .server
        .authorize_device("android-1", "Pixel 7", &token(), 1)
        .await
        .expect("authorize");
    assert!(fixture.server.revoke_device("android-1").await.expect("revoke"));
    let address = fixture.start().await;

    let mut socket = fixture.connect(&address).await;
    socket
        .send(WsMessage::Binary(hello("android-1", &token()).into()))
        .await
        .expect("send hello");
    assert_eq!(next_error(&mut socket).await, ErrorCode::AuthFailed);
    fixture.server.stop().await;
}

#[tokio::test]
async fn stopping_releases_the_port_and_is_idempotent() {
    let fixture = fixture();
    let stopped = fixture.server.status().await;
    assert!(!stopped.running);
    assert!(stopped.fingerprint.is_some(), "identity is exposed to the UI");

    let running = fixture.server.start(false).await.expect("start");
    assert!(running.running);
    let port = running.port.expect("port");
    assert!(port > 0);
    assert_eq!(running.address.as_deref(), Some("127.0.0.1"));
    // Starting twice must not create a second listener or move the address.
    assert_eq!(fixture.server.start(false).await.expect("restart"), running);

    assert!(!fixture.server.stop().await.running);
    assert!(!fixture.server.stop().await.running);

    // Rebinding the same port proves the previous listener was dropped.
    let listener = tokio::net::TcpListener::bind(("127.0.0.1", port))
        .await
        .expect("port released");
    drop(listener);
    assert!(fixture.sink.lines().iter().any(|line| line == "stopped"));
}

#[tokio::test]
async fn lan_listening_stays_closed_until_discovery_ships() {
    let fixture = fixture();
    let error = fixture.server.start(true).await.expect_err("lan refused");
    assert!(error.contains("配对"));
    assert!(!fixture.server.status().await.running);
}

#[tokio::test]
async fn service_refuses_to_start_without_an_identity() {
    let directory = std::env::temp_dir().join(format!(
        "voicebridge-no-identity-{}",
        std::process::id()
    ));
    let devices = DeviceStore::load(directory.join("devices.json")).expect("devices");
    let server = Server::with_port(Arc::new(MemorySink::default()), devices, 0);
    let error = server.start(false).await.expect_err("identity required");
    assert!(error.contains("证书"));
    let _ = std::fs::remove_dir_all(&directory);
}

#[tokio::test]
async fn oversized_frames_are_refused_without_buffering() {
    let fixture = fixture();
    let address = fixture.start().await;

    let mut socket = fixture.connect(&address).await;
    let payload = vec![b'a'; protocol::MAX_MESSAGE_BYTES + 1024];
    let _ = socket.send(WsMessage::Binary(payload.into())).await;
    // The connection is torn down instead of reading the oversized frame into memory.
    assert_closed(&mut socket).await;
    assert!(fixture
        .sink
        .lines()
        .iter()
        .any(|line| line == "read-failed" || line.starts_with("frame-handled")));
    fixture.server.stop().await;
}

#[tokio::test]
async fn connection_limit_is_enforced() {
    let fixture = fixture();
    let address = fixture.start().await;

    // Hold handshakes open without finishing the WebSocket upgrade.
    let mut idle = Vec::new();
    for _ in 0..MAX_CONNECTIONS {
        idle.push(fixture.tls(&address).await);
    }
    let extra = tokio::time::timeout(Duration::from_secs(5), fixture.try_tls(&address))
        .await
        .expect("rejection is immediate");
    assert!(extra.is_err(), "connection beyond the cap was accepted");
    assert!(fixture
        .sink
        .lines()
        .iter()
        .any(|line| line.contains("too-many-connections")));
    drop(idle);
    fixture.server.stop().await;
}
