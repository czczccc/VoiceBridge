use std::fmt::Write as _;
use std::fs;
use std::io;
use std::path::Path;

use rcgen::generate_simple_self_signed;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use sha2::{Digest, Sha256};

const CERT_FILE: &str = "identity.crt.der";
const KEY_FILE: &str = "identity.key.der";

/// Long-lived self-signed identity for the local WSS listener.
///
/// The private key is only ever handed to rustls as DER. It is never logged,
/// serialized into diagnostics, or sent to a client. Peers verify the
/// certificate by fingerprint, so the key must stay stable across restarts.
pub struct Identity {
    certificate: CertificateDer<'static>,
    private_key: Vec<u8>,
    fingerprint: String,
}

#[derive(Debug, PartialEq, Eq)]
pub enum IdentityError {
    /// The identity files could not be read or written.
    Io,
    /// Certificate generation failed.
    Generate,
    /// Only one of the two identity files exists, or a file is empty.
    Corrupt,
}

impl std::fmt::Display for IdentityError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let text = match self {
            Self::Io => "无法读写本机身份文件",
            Self::Generate => "无法生成本机证书",
            Self::Corrupt => "本机身份文件不完整，请删除后重新生成",
        };
        formatter.write_str(text)
    }
}

impl std::error::Error for IdentityError {}

impl Identity {
    /// Reads a saved identity, or creates and persists one on first use.
    ///
    /// A half-present pair is treated as corrupt instead of being silently
    /// regenerated: replacing the key would invalidate every paired device.
    pub fn load_or_create(directory: &Path) -> Result<Self, IdentityError> {
        let certificate_path = directory.join(CERT_FILE);
        let private_key_path = directory.join(KEY_FILE);
        match (fs::read(&certificate_path), fs::read(&private_key_path)) {
            (Ok(certificate), Ok(private_key)) => Self::from_der(certificate, private_key),
            (Err(certificate_error), Err(key_error))
                if certificate_error.kind() == io::ErrorKind::NotFound
                    && key_error.kind() == io::ErrorKind::NotFound =>
            {
                let generated = Self::generate()?;
                fs::create_dir_all(directory).map_err(|_| IdentityError::Io)?;
                // Key first: an interrupted write leaves an incomplete pair, which
                // fails closed instead of silently rotating the identity.
                fs::write(&private_key_path, &generated.private_key)
                    .map_err(|_| IdentityError::Io)?;
                fs::write(&certificate_path, generated.certificate.as_ref())
                    .map_err(|_| IdentityError::Io)?;
                Ok(generated)
            }
            _ => Err(IdentityError::Corrupt),
        }
    }

    fn from_der(certificate: Vec<u8>, private_key: Vec<u8>) -> Result<Self, IdentityError> {
        if certificate.is_empty() || private_key.is_empty() {
            return Err(IdentityError::Corrupt);
        }
        let fingerprint = fingerprint_of(&certificate);
        Ok(Self {
            certificate: CertificateDer::from(certificate),
            private_key,
            fingerprint,
        })
    }

    fn generate() -> Result<Self, IdentityError> {
        let generated = generate_simple_self_signed(vec![
            "voicebridge.local".to_string(),
            "localhost".to_string(),
        ])
        .map_err(|_| IdentityError::Generate)?;
        let certificate = generated.cert.der().clone();
        let private_key = generated.signing_key.serialize_der();
        let fingerprint = fingerprint_of(certificate.as_ref());
        Ok(Self {
            certificate,
            private_key,
            fingerprint,
        })
    }

    pub fn certificate(&self) -> CertificateDer<'static> {
        self.certificate.clone()
    }

    pub fn private_key(&self) -> PrivateKeyDer<'static> {
        PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(self.private_key.clone()))
    }

    /// `SHA256:` plus 64 lowercase hex digits. Safe to show in the UI and mDNS.
    pub fn fingerprint(&self) -> &str {
        &self.fingerprint
    }
}

fn fingerprint_of(certificate: &[u8]) -> String {
    let digest = Sha256::digest(certificate);
    let mut text = String::with_capacity(7 + 64);
    text.push_str("SHA256:");
    for byte in digest {
        let _ = write!(text, "{byte:02x}");
    }
    text
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};

    fn temporary_directory() -> PathBuf {
        static COUNTER: AtomicU64 = AtomicU64::new(0);
        let unique = format!(
            "voicebridge-identity-{}-{}",
            std::process::id(),
            COUNTER.fetch_add(1, Ordering::Relaxed)
        );
        std::env::temp_dir().join(unique)
    }

    fn assert_fingerprint(value: &str) {
        let hex = value.strip_prefix("SHA256:").expect("prefix");
        assert_eq!(hex.len(), 64);
        assert!(hex
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte)));
    }

    #[test]
    fn creates_and_reuses_the_same_identity() {
        let directory = temporary_directory();
        let first = Identity::load_or_create(&directory).expect("create");
        assert_fingerprint(first.fingerprint());
        assert!(!first.certificate().is_empty());
        assert!(matches!(first.private_key(), PrivateKeyDer::Pkcs8(_)));

        let second = Identity::load_or_create(&directory).expect("reload");
        assert_eq!(first.fingerprint(), second.fingerprint());
        assert_eq!(first.certificate(), second.certificate());

        assert!(directory.join(CERT_FILE).is_file());
        assert!(directory.join(KEY_FILE).is_file());
        let _ = fs::remove_dir_all(&directory);
    }

    #[test]
    fn interrupted_pair_fails_closed() {
        let directory = temporary_directory();
        fs::create_dir_all(&directory).unwrap();
        fs::write(directory.join(CERT_FILE), b"certificate").unwrap();
        assert_eq!(
            Identity::load_or_create(&directory).err(),
            Some(IdentityError::Corrupt)
        );
        let _ = fs::remove_dir_all(&directory);
    }

    #[test]
    fn empty_files_are_rejected() {
        let directory = temporary_directory();
        fs::create_dir_all(&directory).unwrap();
        fs::write(directory.join(CERT_FILE), b"").unwrap();
        fs::write(directory.join(KEY_FILE), b"").unwrap();
        assert_eq!(
            Identity::load_or_create(&directory).err(),
            Some(IdentityError::Corrupt)
        );
        let _ = fs::remove_dir_all(&directory);
    }

    #[test]
    fn distinct_identities_have_distinct_fingerprints() {
        let first = Identity::generate().unwrap();
        let second = Identity::generate().unwrap();
        assert_ne!(first.fingerprint(), second.fingerprint());
    }
}
