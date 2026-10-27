use std::fmt::Write as _;

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine as _;
use rand::rngs::OsRng;
use rand::RngCore;
use sha2::{Digest, Sha256};

pub mod devices;
pub mod session;

/// Domain separator for the numeric comparison code. Changing it invalidates
/// every in-flight pairing, so it must stay stable.
pub const SAS_PREFIX: &str = "voicebridge-sas-v1";
pub const NONCE_BYTES: usize = 32;
pub const TOKEN_BYTES: usize = 32;

#[derive(Debug, PartialEq, Eq)]
pub struct RandomError;

impl std::fmt::Display for RandomError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("系统随机源不可用")
    }
}

impl std::error::Error for RandomError {}

/// Unpadded base64url of `bytes` random bytes, as required by PROTOCOL.md.
pub fn random_base64url(bytes: usize) -> Result<String, RandomError> {
    let mut buffer = vec![0u8; bytes];
    OsRng.try_fill_bytes(&mut buffer).map_err(|_| RandomError)?;
    Ok(URL_SAFE_NO_PAD.encode(buffer))
}

/// Bluetooth-style numeric comparison code.
///
/// Both ends must feed in the fingerprint the *client actually observed* during
/// the TLS handshake, plus both nonces. A man-in-the-middle terminates two TLS
/// sessions with different certificates, so the two codes differ and the user
/// rejects the pairing. The code is never transmitted.
pub fn sas_code(fingerprint: &str, client_nonce: &str, server_nonce: &str) -> String {
    let mut digest = Sha256::new();
    digest.update(SAS_PREFIX.as_bytes());
    digest.update(fingerprint.as_bytes());
    digest.update(client_nonce.as_bytes());
    digest.update(server_nonce.as_bytes());
    let hash = digest.finalize();
    let value = u32::from_be_bytes([hash[0], hash[1], hash[2], hash[3]]) % 1_000_000;
    format!("{value:06}")
}

/// Windows stores only this digest; the raw token lives on the Android device.
pub fn token_hash(token: &str) -> String {
    let digest = Sha256::digest(token.as_bytes());
    let mut text = String::with_capacity(64);
    for byte in digest {
        let _ = write!(text, "{byte:02x}");
    }
    text
}

#[cfg(test)]
mod tests {
    use super::*;

    const FINGERPRINT: &str =
        "SHA256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    fn fixed(character: char) -> String {
        character.to_string().repeat(43)
    }

    /// Cross-checked against an independent implementation in PROTOCOL.md order.
    #[test]
    fn sas_matches_the_documented_derivation() {
        let code = sas_code(FINGERPRINT, &fixed('A'), &fixed('B'));
        assert_eq!(code, "873723");
        assert_eq!(code.len(), 6);
    }

    #[test]
    fn sas_changes_with_every_input() {
        let (client, server) = (fixed('A'), fixed('B'));
        let base = sas_code(FINGERPRINT, &client, &server);
        assert_ne!(base, sas_code(FINGERPRINT, &client, &fixed('D')));
        assert_ne!(base, sas_code(FINGERPRINT, &fixed('C'), &server));
        let other = "SHA256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
        assert_ne!(base, sas_code(other, &client, &server));
    }

    #[test]
    fn sas_is_always_six_digits_with_leading_zeros() {
        for attempt in 0..200 {
            let nonce = random_base64url(NONCE_BYTES).unwrap();
            let code = sas_code(FINGERPRINT, &nonce, &format!("{attempt}"));
            assert_eq!(code.len(), 6, "{code}");
            assert!(code.bytes().all(|byte| byte.is_ascii_digit()), "{code}");
        }
    }

    #[test]
    fn token_hash_matches_sha256_hex() {
        assert_eq!(
            token_hash(&fixed('C')),
            "942014af03195c97fc8a6121a03e1660b89027a538325a101eba426dacd7e587"
        );
    }

    #[test]
    fn random_tokens_are_unpadded_base64url() {
        let first = random_base64url(TOKEN_BYTES).unwrap();
        let second = random_base64url(TOKEN_BYTES).unwrap();
        assert_ne!(first, second);
        assert_eq!(first.len(), 43);
        assert!(!first.contains('='));
        assert!(first
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_'));
    }
}
