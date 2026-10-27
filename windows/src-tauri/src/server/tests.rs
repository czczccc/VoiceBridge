use std::sync::Arc;
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use tokio_tungstenite::tungstenite::Message as WsMessage;

use super::log::MemorySink;
use super::{reply_for, Server, MAX_CONNECTIONS};
use crate::protocol::{self, Body, ErrorCode, Message};

fn text_snapshot(text: &str) -> Vec<u8> {
    protocol::encode(&Message {
        v: 1,
        message_id: "m-test".to_string(),
        sent_at: 1,
        body: Body::TextSnapshot {
            session_id: "s-test".to_string(),
            seq: 1,
            text: text.to_string(),
            selection: None,
            composition: None,
        },
    })
    .unwrap()
}

fn hello() -> Vec<u8> {
    protocol::encode(&Message {
        v: 1,
        message_id: "m-hello".to_string(),
        sent_at: 1,
        body: Body::Hello {
            device_id: "device".to_string(),
            client_version: "0.1.0".to_string(),
            auth: protocol::Auth {
                token: "token".to_string(),
            },
        },
    })
    .unwrap()
}

type Socket =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

async fn read_error_code(socket: &mut Socket) -> ErrorCode {
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
    match protocol::decode(&bytes).expect("error frame decodes").body {
        Body::Error { code, .. } => code,
        other => panic!("unexpected body kind: {:?}", std::mem::discriminant(&other)),
    }
}

/// The server must not keep a refused connection open.
async fn assert_closed(socket: &mut Socket) {
    loop {
        match tokio::time::timeout(Duration::from_secs(5), socket.next()).await {
            Ok(Some(Ok(WsMessage::Close(_)))) | Ok(None) => return,
            Ok(Some(Ok(_))) => continue,
            Ok(Some(Err(_))) => return,
            Err(_) => panic!("server kept the connection open"),
        }
    }
}

async fn running_server(sink: Arc<MemorySink>) -> (Server, String) {
    let server = Server::with_port(sink, 0);
    let status = server.start(false).await.unwrap();
    let address = format!(
        "{}:{}",
        status.address.as_deref().unwrap(),
        status.port.unwrap()
    );
    (server, address)
}

#[test]
fn unsupported_message_types_are_never_authorized() {
    let snapshot = text_snapshot("秘密正文");
    let (code, reply) = reply_for(&protocol::decode(&snapshot).unwrap());
    assert_eq!(code, ErrorCode::AuthFailed);
    let frame = reply.expect("a refusal frame is produced");
    let decoded = protocol::decode(&frame).unwrap();
    match decoded.body {
        Body::Error { code, message } => {
            assert_eq!(code, ErrorCode::AuthFailed);
            assert!(!message.contains("秘密"));
        }
        other => panic!("unexpected body kind: {:?}", std::mem::discriminant(&other)),
    }
    assert!(!String::from_utf8_lossy(&frame).contains("秘密"));
}

#[test]
fn rejections_use_protocol_error_codes() {
    let oversized = vec![b' '; protocol::MAX_MESSAGE_BYTES + 1];
    assert_eq!(
        protocol::decode(&oversized).err(),
        Some(ErrorCode::MessageTooLarge)
    );
    assert_eq!(
        protocol::decode(b"not-json").err(),
        Some(ErrorCode::InvalidMessage)
    );
    assert_eq!(
        protocol::decode(br#"{"v":2,"type":"hello"}"#).err(),
        Some(ErrorCode::UnsupportedProtocol)
    );
}

#[tokio::test]
async fn unauthorized_frames_are_refused_without_logging_bodies() {
    let sink = Arc::new(MemorySink::default());
    let (server, address) = running_server(sink.clone()).await;

    let secret = "这是一段不应出现在日志里的正文";
    for (payload, expected) in [
        (b"not-json".to_vec(), ErrorCode::InvalidMessage),
        (text_snapshot(secret), ErrorCode::AuthFailed),
        (hello(), ErrorCode::AuthFailed),
    ] {
        let (mut socket, _) = tokio_tungstenite::connect_async(format!("ws://{address}"))
            .await
            .expect("connect");
        socket
            .send(WsMessage::Binary(payload.into()))
            .await
            .expect("send");
        assert_eq!(read_error_code(&mut socket).await, expected);
        assert_closed(&mut socket).await;
    }

    let lines = sink.lines();
    assert!(lines.iter().any(|line| line.starts_with("started addr=")));
    assert!(lines.iter().any(|line| line.contains("AuthFailed")));
    for line in &lines {
        assert!(!line.contains(secret), "log leaked body: {line}");
        assert!(!line.contains("token"), "log leaked credential: {line}");
    }
    server.stop().await;
}

#[tokio::test]
async fn stopping_releases_the_port_and_is_idempotent() {
    let sink = Arc::new(MemorySink::default());
    let server = Server::with_port(sink.clone(), 0);
    assert_eq!(server.status().await, super::Status::stopped());

    let running = server.start(false).await.unwrap();
    assert!(running.running);
    assert!(running.port.unwrap() > 0);
    assert_eq!(running.address.as_deref(), Some("127.0.0.1"));
    // Starting twice must not create a second listener or move the address.
    assert_eq!(server.start(false).await.unwrap(), running);

    assert_eq!(server.stop().await, super::Status::stopped());
    assert_eq!(server.stop().await, super::Status::stopped());

    // Rebinding the same port proves the previous listener was dropped.
    let listener = tokio::net::TcpListener::bind(("127.0.0.1", running.port.unwrap()))
        .await
        .expect("port released");
    drop(listener);
    assert!(sink.lines().iter().any(|line| line == "stopped"));
}

#[tokio::test]
async fn lan_listening_stays_closed_until_authentication_exists() {
    let server = Server::with_port(Arc::new(MemorySink::default()), 0);
    let error = server.start(true).await.unwrap_err();
    assert!(error.contains("配对"));
    assert!(!server.status().await.running);
}

#[tokio::test]
async fn oversized_frames_are_refused_without_buffering() {
    let sink = Arc::new(MemorySink::default());
    let (server, address) = running_server(sink.clone()).await;

    let (mut socket, _) = tokio_tungstenite::connect_async(format!("ws://{address}"))
        .await
        .expect("connect");
    let payload = vec![b'a'; protocol::MAX_MESSAGE_BYTES + 1024];
    let _ = socket.send(WsMessage::Binary(payload.into())).await;
    // The connection is torn down instead of reading the oversized frame into memory.
    assert_closed(&mut socket).await;
    assert!(sink
        .lines()
        .iter()
        .any(|line| line == "read-failed" || line.starts_with("frame-handled")));
    server.stop().await;
}

#[tokio::test]
async fn connection_limit_is_enforced() {
    let sink = Arc::new(MemorySink::default());
    let (server, address) = running_server(sink.clone()).await;

    // Hold handshakes open without sending frames so the budget stays consumed.
    let mut idle = Vec::new();
    for _ in 0..MAX_CONNECTIONS {
        let (socket, _) = tokio_tungstenite::connect_async(format!("ws://{address}"))
            .await
            .expect("connect");
        idle.push(socket);
    }
    let extra = tokio::time::timeout(
        Duration::from_secs(5),
        tokio_tungstenite::connect_async(format!("ws://{address}")),
    )
    .await
    .expect("rejection is immediate");
    assert!(extra.is_err(), "connection beyond the cap was accepted");
    assert!(sink
        .lines()
        .iter()
        .any(|line| line.contains("too-many-connections")));
    drop(idle);
    server.stop().await;
}
