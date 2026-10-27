use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use subtle::ConstantTimeEq;

use super::token_hash;

/// One paired Android device. Only the token digest is stored.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Device {
    pub device_id: String,
    pub name: String,
    pub token_hash: String,
    pub last_seen: u64,
}

#[derive(Default, Serialize, Deserialize)]
struct StoreFile {
    devices: Vec<Device>,
}

/// Authorized devices persisted as JSON next to the local identity.
pub struct DeviceStore {
    path: PathBuf,
    devices: Vec<Device>,
}

impl DeviceStore {
    /// Starts from an empty authorization list without touching disk.
    pub fn empty(path: PathBuf) -> Self {
        Self {
            path,
            devices: Vec::new(),
        }
    }

    /// Missing file means "no devices yet"; malformed JSON is an error so a
    /// damaged file is never silently treated as an empty authorization list.
    pub fn load(path: PathBuf) -> io::Result<Self> {
        let devices = match fs::read(&path) {
            Ok(bytes) => {
                let file: StoreFile = serde_json::from_slice(&bytes).map_err(|_| {
                    io::Error::new(io::ErrorKind::InvalidData, "device store is not valid JSON")
                })?;
                file.devices
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => Vec::new(),
            Err(error) => return Err(error),
        };
        Ok(Self { path, devices })
    }

    pub fn list(&self) -> &[Device] {
        &self.devices
    }

    /// Constant-time token comparison. The device id must match as well, so a
    /// token copied from one device cannot authorize another.
    pub fn is_authorized(&self, device_id: &str, token: &str) -> bool {
        let candidate = token_hash(token);
        self.devices
            .iter()
            .filter(|device| device.device_id == device_id)
            .any(|device| bool::from(device.token_hash.as_bytes().ct_eq(candidate.as_bytes())))
    }

    /// Adds a device, or replaces an existing record for the same device id.
    pub fn authorize(
        &mut self,
        device_id: &str,
        name: &str,
        token: &str,
        now: u64,
    ) -> io::Result<()> {
        let record = Device {
            device_id: device_id.to_string(),
            name: name.to_string(),
            token_hash: token_hash(token),
            last_seen: now,
        };
        match self
            .devices
            .iter_mut()
            .find(|device| device.device_id == record.device_id)
        {
            Some(existing) => *existing = record,
            None => self.devices.push(record),
        }
        self.save()
    }

    pub fn touch(&mut self, device_id: &str, now: u64) -> io::Result<()> {
        if let Some(device) = self
            .devices
            .iter_mut()
            .find(|device| device.device_id == device_id)
        {
            device.last_seen = now;
            self.save()?;
        }
        Ok(())
    }

    pub fn revoke(&mut self, device_id: &str) -> io::Result<bool> {
        let before = self.devices.len();
        self.devices.retain(|device| device.device_id != device_id);
        if self.devices.len() == before {
            return Ok(false);
        }
        self.save()?;
        Ok(true)
    }

    fn save(&self) -> io::Result<()> {
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent)?;
        }
        let file = StoreFile {
            devices: self.devices.clone(),
        };
        let bytes = serde_json::to_vec_pretty(&file)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
        let temporary = temporary_path(&self.path);
        fs::write(&temporary, bytes)?;
        // Rename replaces the previous file atomically on both Windows and Unix.
        fs::rename(&temporary, &self.path).inspect_err(|_| {
            let _ = fs::remove_file(&temporary);
        })
    }
}

fn temporary_path(path: &Path) -> PathBuf {
    let mut name = path.as_os_str().to_os_string();
    name.push(".tmp");
    PathBuf::from(name)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};

    fn temporary_path_for_store() -> PathBuf {
        static COUNTER: AtomicU64 = AtomicU64::new(0);
        let unique = format!(
            "voicebridge-devices-{}-{}",
            std::process::id(),
            COUNTER.fetch_add(1, Ordering::Relaxed)
        );
        std::env::temp_dir().join(unique).join("devices.json")
    }

    #[test]
    fn authorizes_and_reloads_devices() {
        let path = temporary_path_for_store();
        let token = "A".repeat(43);
        let mut store = DeviceStore::load(path.clone()).unwrap();
        assert!(store.list().is_empty());
        store.authorize("android-1", "Pixel 7", &token, 1).unwrap();

        let reloaded = DeviceStore::load(path.clone()).unwrap();
        assert_eq!(reloaded.list().len(), 1);
        assert_eq!(reloaded.list()[0].name, "Pixel 7");
        assert!(reloaded.is_authorized("android-1", &token));
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn wrong_token_or_device_is_rejected() {
        let path = temporary_path_for_store();
        let token = "B".repeat(43);
        let mut store = DeviceStore::load(path.clone()).unwrap();
        store.authorize("android-1", "Pixel 7", &token, 1).unwrap();

        assert!(!store.is_authorized("android-1", &"C".repeat(43)));
        assert!(!store.is_authorized("android-1", ""));
        assert!(!store.is_authorized("android-2", &token));
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn revoking_removes_access_immediately() {
        let path = temporary_path_for_store();
        let token = "D".repeat(43);
        let mut store = DeviceStore::load(path.clone()).unwrap();
        store.authorize("android-1", "Pixel 7", &token, 1).unwrap();
        assert!(store.revoke("android-1").unwrap());
        assert!(!store.revoke("android-1").unwrap());
        assert!(!store.is_authorized("android-1", &token));
        assert!(DeviceStore::load(path.clone()).unwrap().list().is_empty());
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn reauthorizing_replaces_the_previous_token() {
        let path = temporary_path_for_store();
        let mut store = DeviceStore::load(path.clone()).unwrap();
        store.authorize("android-1", "Pixel 7", &"E".repeat(43), 1).unwrap();
        store.authorize("android-1", "Pixel 7", &"F".repeat(43), 2).unwrap();
        assert_eq!(store.list().len(), 1);
        assert!(!store.is_authorized("android-1", &"E".repeat(43)));
        assert!(store.is_authorized("android-1", &"F".repeat(43)));
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn the_store_file_never_contains_the_raw_token() {
        let path = temporary_path_for_store();
        let token = "G".repeat(43);
        let mut store = DeviceStore::load(path.clone()).unwrap();
        store.authorize("android-1", "Pixel 7", &token, 1).unwrap();
        let text = fs::read_to_string(&path).unwrap();
        assert!(!text.contains(&token));
        assert!(text.contains(&token_hash(&token)));
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn damaged_store_is_an_error_not_an_empty_store() {
        let path = temporary_path_for_store();
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(&path, b"not json").unwrap();
        let error = DeviceStore::load(path.clone())
            .err()
            .expect("damaged store must fail");
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn touching_updates_last_seen_without_changing_access() {
        let path = temporary_path_for_store();
        let token = "H".repeat(43);
        let mut store = DeviceStore::load(path.clone()).unwrap();
        store.authorize("android-1", "Pixel 7", &token, 1).unwrap();
        store.touch("android-1", 99).unwrap();
        let reloaded = DeviceStore::load(path.clone()).unwrap();
        assert_eq!(reloaded.list()[0].last_seen, 99);
        assert!(reloaded.is_authorized("android-1", &token));
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }
}
