use super::*;
use serde_json::{json, Value};

fn fixtures() -> Vec<Value> {
    serde_json::from_str(include_str!("../../../../protocol/fixtures/messages.json")).unwrap()
}

fn read(value: &Value) -> Result<Message, ErrorCode> {
    decode(&serde_json::to_vec(value).unwrap())
}

#[test]
fn shared_messages_round_trip() {
    for value in fixtures() {
        let first = read(&value).unwrap();
        let bytes = encode(&first).unwrap();
        let second = decode(&bytes).unwrap();
        assert!(first == second);
    }
}

#[test]
fn shared_invalid_messages_are_rejected() {
    let cases: Vec<Value> =
        serde_json::from_str(include_str!("../../../../protocol/fixtures/invalid.json")).unwrap();
    for case in cases {
        let expected: ErrorCode = serde_json::from_value(case["error"].clone()).unwrap();
        assert_eq!(read(&case["message"]).err(), Some(expected));
    }
}

#[test]
fn every_required_field_is_required() {
    let fields = [
        vec!["deviceId", "clientVersion", "auth"],
        vec![
            "serverVersion",
            "connectionId",
            "capabilities",
            "authenticated",
        ],
        vec!["sessionId", "target"],
        vec!["sessionId", "seq", "text"],
        vec!["sessionId", "seq", "status", "latencyMs"],
        vec!["sessionId", "reason"],
        vec!["eventId", "originDeviceId", "direction", "text"],
        vec!["code", "message"],
        vec!["deviceId", "deviceName", "clientVersion", "clientNonce"],
        vec![
            "pairingId",
            "serverDeviceId",
            "serverName",
            "serverNonce",
            "certFingerprint",
            "expiresAt",
        ],
        vec!["pairingId", "confirmed"],
        vec!["pairingId", "status", "serverDeviceId", "serverName"],
    ];
    for (message, required) in fixtures().into_iter().zip(fields) {
        for field in ["v", "type", "messageId", "sentAt"]
            .into_iter()
            .chain(required)
        {
            let mut missing = message.clone();
            missing.as_object_mut().unwrap().remove(field);
            assert_eq!(read(&missing).err(), Some(ErrorCode::InvalidMessage));
            let mut null = message.clone();
            null[field] = Value::Null;
            assert_eq!(read(&null).err(), Some(ErrorCode::InvalidMessage));
        }
    }
}

#[test]
fn utf8_size_limit_applies_to_entire_frame_and_encoding() {
    let mut message = fixtures()[3].clone();
    message["text"] = json!("中".repeat(MAX_MESSAGE_BYTES / 3 + 1));
    let typed: Message = serde_json::from_value(message.clone()).unwrap();
    assert_eq!(read(&message).err(), Some(ErrorCode::MessageTooLarge));
    assert_eq!(encode(&typed).err(), Some(ErrorCode::MessageTooLarge));
    // Whitespace counts toward the transport limit, even for a small payload.
    let bytes = serde_json::to_vec(&fixtures()[0]).unwrap();
    let mut padded = vec![b' '; MAX_MESSAGE_BYTES - bytes.len()];
    padded.extend_from_slice(&bytes);
    assert!(decode(&padded).is_ok());
    padded.push(b' ');
    assert_eq!(decode(&padded).err(), Some(ErrorCode::MessageTooLarge));
}

#[test]
fn sequence_and_utf16_boundaries() {
    let mut message = fixtures()[3].clone();
    for seq in [
        json!(0),
        json!(-1),
        json!(1.5),
        json!("42"),
        json!(MAX_INTEGER + 1),
    ] {
        message["seq"] = seq;
        assert_eq!(read(&message).err(), Some(ErrorCode::InvalidMessage));
    }
    message["seq"] = json!(MAX_INTEGER);
    message["selection"] = json!({"start":4,"end":0});
    assert!(read(&message).is_ok());
    message["selection"] = json!({"start":3,"end":3});
    assert_eq!(read(&message).err(), Some(ErrorCode::InvalidMessage));
    message["selection"] = Value::Null;
    message["composition"] = json!({"start":4,"end":2});
    assert_eq!(read(&message).err(), Some(ErrorCode::InvalidMessage));
}

#[test]
fn only_paired_results_carry_a_credential() {
    let base = |status: PairStatus, credential: Option<String>| Body::PairResult {
        pairing_id: "pairing-1".to_string(),
        status,
        token: credential,
        server_device_id: "windows-device-id".to_string(),
        server_name: "DESKTOP-ABC".to_string(),
    };
    let message = |body: Body| Message {
        v: VERSION,
        message_id: "m".to_string(),
        sent_at: 0,
        body,
    };
    let credential = || Some("C".repeat(43));

    assert!(encode(&message(base(PairStatus::Paired, credential()))).is_ok());
    for status in [
        PairStatus::Rejected,
        PairStatus::Expired,
        PairStatus::Mismatch,
        PairStatus::Busy,
    ] {
        assert!(encode(&message(base(status, None))).is_ok());
        assert_eq!(
            encode(&message(base(status, credential()))).err(),
            Some(ErrorCode::InvalidMessage)
        );
    }
    assert_eq!(
        encode(&message(base(PairStatus::Paired, None))).err(),
        Some(ErrorCode::InvalidMessage)
    );
    // A padded or non-base64url credential must never round-trip.
    for bad in ["C".repeat(43) + "=", "not/valid+token".to_string()] {
        assert_eq!(
            encode(&message(base(PairStatus::Paired, Some(bad)))).err(),
            Some(ErrorCode::InvalidMessage)
        );
    }
}

#[test]
fn optional_extensions_are_ignored_but_types_are_strict() {
    let mut message = fixtures()[3].clone();
    message["futureOptional"] = json!({"feature": true});
    message.as_object_mut().unwrap().remove("composition");
    assert!(read(&message).is_ok());
    message["text"] = json!(123);
    assert_eq!(read(&message).err(), Some(ErrorCode::InvalidMessage));
    assert_eq!(decode(b"not json").err(), Some(ErrorCode::InvalidMessage));
    assert_eq!(decode(&[0xff]).err(), Some(ErrorCode::InvalidMessage));
}
