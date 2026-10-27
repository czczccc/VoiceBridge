use std::collections::HashMap;
use std::net::Ipv4Addr;

use mdns_sd::{ServiceDaemon, ServiceInfo};

/// DNS-SD type Android browses. Discovery is unauthenticated: it only tells the
/// phone where to connect, and the certificate fingerprint is verified by the
/// client before pairing.
pub const SERVICE_TYPE: &str = "_voicebridge._tcp.local.";
const HOST_NAME: &str = "voicebridge.local.";

/// Advertises this computer while the service listens on the LAN.
///
/// Dropping the value unregisters the record and stops the daemon, so a stopped
/// service never stays visible on the network.
pub struct Advertiser {
    daemon: ServiceDaemon,
    fullname: String,
}

impl Advertiser {
    pub fn start(
        instance: &str,
        port: u16,
        address: Ipv4Addr,
        properties: HashMap<String, String>,
    ) -> Result<Self, String> {
        let daemon = ServiceDaemon::new().map_err(|_| "无法启动 mDNS 服务".to_string())?;
        let info = ServiceInfo::new(
            SERVICE_TYPE,
            instance,
            HOST_NAME,
            address.to_string(),
            port,
            properties,
        )
        .map_err(|_| "无法创建 mDNS 服务记录".to_string())?;
        let fullname = info.get_fullname().to_string();
        daemon
            .register(info)
            .map_err(|_| "无法广播 mDNS 服务".to_string())?;
        Ok(Self { daemon, fullname })
    }
}

impl Drop for Advertiser {
    fn drop(&mut self) {
        let _ = self.daemon.unregister(&self.fullname);
        let _ = self.daemon.shutdown();
    }
}

/// TXT record shared with the Android client. Keys must stay in sync with
/// `DiscoveryRecord` on the phone.
pub fn properties(
    device_id: &str,
    device_name: &str,
    fingerprint: &str,
) -> HashMap<String, String> {
    HashMap::from([
        ("v".to_string(), "1".to_string()),
        ("id".to_string(), device_id.to_string()),
        ("name".to_string(), device_name.to_string()),
        ("fp".to_string(), fingerprint.to_string()),
    ])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn txt_record_carries_the_contract_keys() {
        let values = properties("windows-abc", "DESKTOP", "SHA256:00");
        assert_eq!(values.get("v").map(String::as_str), Some("1"));
        assert_eq!(values.get("id").map(String::as_str), Some("windows-abc"));
        assert_eq!(values.get("name").map(String::as_str), Some("DESKTOP"));
        assert_eq!(values.get("fp").map(String::as_str), Some("SHA256:00"));
    }
}
