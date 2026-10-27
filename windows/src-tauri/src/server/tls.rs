use std::sync::Arc;

use tokio_rustls::TlsAcceptor;

use crate::identity::Identity;

/// Builds the WSS acceptor for the persisted identity.
///
/// The certificate is self-signed, so peers must pin its fingerprint instead of
/// trusting a public CA. Installing the ring provider explicitly keeps the
/// process working even if another dependency also enables a rustls provider.
pub fn acceptor(identity: &Identity) -> Result<TlsAcceptor, String> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let config = rustls::ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(vec![identity.certificate()], identity.private_key())
        .map_err(|_| "无法加载本机证书".to_string())?;
    Ok(TlsAcceptor::from(Arc::new(config)))
}
