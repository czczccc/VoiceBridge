use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use tokio::sync::{oneshot, Mutex as AsyncMutex};

use super::devices::DeviceStore;
use super::{random_base64url, NONCE_BYTES, TOKEN_BYTES};

/// How long a pairing attempt stays open for the user to approve it.
pub const PAIRING_TTL_MS: u64 = 120_000;
/// Attempts allowed inside [RATE_LIMIT_WINDOW_MS] before the server answers `busy`.
const MAX_ATTEMPTS: usize = 5;
const RATE_LIMIT_WINDOW_MS: u64 = 60_000;

#[derive(Debug, PartialEq, Eq)]
pub enum PairError {
    /// Another attempt is in progress, or the request rate is too high.
    Busy,
    /// The local random source failed.
    Random,
    /// The device list could not be written.
    Store,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Challenge {
    pub pairing_id: String,
    pub server_nonce: String,
    pub expires_at: u64,
}

/// What the UI needs to ask the local user. Deliberately no token and no nonce.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PendingPairing {
    pub device_id: String,
    pub device_name: String,
    pub expires_at: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PairOutcome {
    Paired { token: String },
    Rejected,
    Expired,
}

/// Result of a client confirmation arriving over the socket.
pub enum ClientConfirm {
    /// Both sides already agreed; reply immediately.
    Complete(PairOutcome),
    /// Waiting for the local user; resolve the receiver to finish.
    Waiting(oneshot::Receiver<PairOutcome>),
    /// No such attempt, or it expired.
    Stale,
}

struct Attempt {
    pairing_id: String,
    device_id: String,
    device_name: String,
    expires_at: u64,
    client_decision: Option<bool>,
    local_decision: Option<bool>,
    /// Set by whichever side finishes the attempt, so only one token is issued
    /// even if approval and confirmation land at the same instant.
    completing: bool,
    notify: Option<oneshot::Sender<PairOutcome>>,
}

pub type Clock = Box<dyn Fn() -> u64 + Send + Sync>;

/// Server-side pairing session. Only one attempt is open at a time, which keeps
/// the approval popup unambiguous for the local user.
pub struct PairingManager {
    devices: Arc<AsyncMutex<DeviceStore>>,
    server_device_id: String,
    server_name: String,
    attempt: Mutex<Option<Attempt>>,
    last: Mutex<Option<(String, PairOutcome)>>,
    attempts: Mutex<Vec<u64>>,
    clock: Clock,
}

impl PairingManager {
    pub fn new(
        devices: Arc<AsyncMutex<DeviceStore>>,
        server_device_id: String,
        server_name: String,
    ) -> Self {
        Self::with_clock(devices, server_device_id, server_name, Box::new(now_millis))
    }

    pub fn with_clock(
        devices: Arc<AsyncMutex<DeviceStore>>,
        server_device_id: String,
        server_name: String,
        clock: Clock,
    ) -> Self {
        Self {
            devices,
            server_device_id,
            server_name,
            attempt: Mutex::new(None),
            last: Mutex::new(None),
            attempts: Mutex::new(Vec::new()),
            clock,
        }
    }

    pub fn server_device_id(&self) -> &str {
        &self.server_device_id
    }

    pub fn server_name(&self) -> &str {
        &self.server_name
    }

    fn now(&self) -> u64 {
        (self.clock)()
    }

    /// Starts an attempt. `Busy` covers both a concurrent attempt and rate limiting.
    pub fn begin(&self, device_id: &str, device_name: &str) -> Result<Challenge, PairError> {
        let now = self.now();
        self.prune_expired(now);

        let mut slot = self.attempt.lock().map_err(|_| PairError::Busy)?;
        if slot.is_some() {
            return Err(PairError::Busy);
        }
        self.record_attempt(now)?;
        let pairing_id = random_base64url(12).map_err(|_| PairError::Random)?;
        let server_nonce = random_base64url(NONCE_BYTES).map_err(|_| PairError::Random)?;
        let expires_at = now + PAIRING_TTL_MS;
        *slot = Some(Attempt {
            pairing_id: pairing_id.clone(),
            device_id: device_id.to_string(),
            device_name: device_name.to_string(),
            expires_at,
            client_decision: None,
            local_decision: None,
            completing: false,
            notify: None,
        });
        Ok(Challenge {
            pairing_id,
            server_nonce,
            expires_at,
        })
    }

    pub fn pending(&self) -> Option<PendingPairing> {
        let now = self.now();
        let mut slot = self.attempt.lock().ok()?;
        if slot
            .as_ref()
            .is_some_and(|attempt| attempt.expires_at <= now)
        {
            *slot = None;
        }
        slot.as_ref().map(|attempt| PendingPairing {
            device_id: attempt.device_id.clone(),
            device_name: attempt.device_name.clone(),
            expires_at: attempt.expires_at,
        })
    }

    /// Local user pressed Allow/Deny. Completes the attempt if the client already confirmed.
    pub async fn local_decision(&self, accepted: bool) -> Result<(), PairError> {
        let (pairing_id, both) = {
            let mut slot = self.attempt.lock().map_err(|_| PairError::Busy)?;
            let Some(attempt) = slot.as_mut() else {
                return Ok(());
            };
            attempt.local_decision = Some(accepted);
            let both = attempt.client_decision.is_some();
            (attempt.pairing_id.clone(), both)
        };
        if !both {
            return Ok(());
        }
        if accepted {
            if let Some((device_id, device_name)) = self.claim(&pairing_id) {
                let outcome = self.issue(&device_id, &device_name).await;
                self.finish(&pairing_id, outcome);
            }
        } else {
            self.finish(&pairing_id, PairOutcome::Rejected);
        }
        Ok(())
    }

    /// Client sent `pair.confirm`. Returns what to send back, or a receiver to await.
    pub async fn client_confirm(&self, pairing_id: &str, accepted: bool) -> ClientConfirm {
        let local = {
            let Ok(mut slot) = self.attempt.lock() else {
                return ClientConfirm::Stale;
            };
            let Some(attempt) = slot.as_mut() else {
                return ClientConfirm::Stale;
            };
            if attempt.pairing_id != pairing_id {
                return ClientConfirm::Stale;
            }
            if attempt.expires_at <= self.now() {
                return ClientConfirm::Stale;
            }
            attempt.client_decision = Some(accepted);
            attempt.local_decision
        };

        if !accepted {
            self.finish(pairing_id, PairOutcome::Rejected);
            return ClientConfirm::Complete(PairOutcome::Rejected);
        }
        match local {
            Some(true) => match self.claim(pairing_id) {
                Some((device_id, device_name)) => {
                    let outcome = self.issue(&device_id, &device_name).await;
                    self.finish(pairing_id, outcome.clone());
                    ClientConfirm::Complete(outcome)
                }
                // Another task is finishing this attempt; return its outcome.
                None => self.completed(pairing_id),
            },
            Some(false) => {
                self.finish(pairing_id, PairOutcome::Rejected);
                ClientConfirm::Complete(PairOutcome::Rejected)
            }
            None => {
                let (sender, receiver) = oneshot::channel();
                let mut registered = false;
                if let Ok(mut slot) = self.attempt.lock() {
                    if let Some(attempt) = slot.as_mut() {
                        if attempt.pairing_id == pairing_id {
                            attempt.notify = Some(sender);
                            registered = true;
                        }
                    }
                }
                if registered {
                    ClientConfirm::Waiting(receiver)
                } else {
                    // Approval finished the attempt between the two locks.
                    self.completed(pairing_id)
                }
            }
        }
    }

    /// Marks the attempt expired. Safe to call for an unknown id.
    ///
    /// Never interrupts a completion in progress: the token may already be
    /// stored, and discarding the outcome would leave a device that can never
    /// authenticate.
    pub fn expire(&self, pairing_id: &str) {
        let Ok(slot) = self.attempt.lock() else {
            return;
        };
        let completing = slot
            .as_ref()
            .filter(|attempt| attempt.pairing_id == pairing_id)
            .is_some_and(|attempt| attempt.completing);
        drop(slot);
        if completing {
            return;
        }
        self.finish(pairing_id, PairOutcome::Expired);
    }

    /// Takes exclusive responsibility for finishing the attempt.
    fn claim(&self, pairing_id: &str) -> Option<(String, String)> {
        let mut slot = self.attempt.lock().ok()?;
        let attempt = slot.as_mut()?;
        if attempt.pairing_id != pairing_id || attempt.completing {
            return None;
        }
        attempt.completing = true;
        Some((attempt.device_id.clone(), attempt.device_name.clone()))
    }

    /// Outcome already produced for `pairing_id`, if any.
    fn completed(&self, pairing_id: &str) -> ClientConfirm {
        match self.last.lock().ok().and_then(|last| {
            last.as_ref()
                .filter(|(id, _)| id == pairing_id)
                .map(|(_, outcome)| outcome.clone())
        }) {
            Some(outcome) => ClientConfirm::Complete(outcome),
            None => ClientConfirm::Stale,
        }
    }

    fn finish(&self, pairing_id: &str, outcome: PairOutcome) {
        let Ok(mut slot) = self.attempt.lock() else {
            return;
        };
        let Some(attempt) = slot.as_mut() else {
            return;
        };
        if attempt.pairing_id != pairing_id {
            return;
        }
        if let Some(sender) = attempt.notify.take() {
            let _ = sender.send(outcome.clone());
        }
        if let Ok(mut last) = self.last.lock() {
            *last = Some((pairing_id.to_string(), outcome));
        }
        *slot = None;
    }

    async fn issue(&self, device_id: &str, device_name: &str) -> PairOutcome {
        let Ok(token) = random_base64url(TOKEN_BYTES) else {
            return PairOutcome::Rejected;
        };
        let now = self.now();
        let stored = self
            .devices
            .lock()
            .await
            .authorize(device_id, device_name, &token, now)
            .is_ok();
        if stored {
            PairOutcome::Paired { token }
        } else {
            PairOutcome::Rejected
        }
    }

    fn record_attempt(&self, now: u64) -> Result<(), PairError> {
        let mut attempts = self.attempts.lock().map_err(|_| PairError::Busy)?;
        attempts.retain(|at| now.saturating_sub(*at) < RATE_LIMIT_WINDOW_MS);
        if attempts.len() >= MAX_ATTEMPTS {
            return Err(PairError::Busy);
        }
        attempts.push(now);
        Ok(())
    }

    fn prune_expired(&self, now: u64) {
        let Ok(mut slot) = self.attempt.lock() else {
            return;
        };
        if slot
            .as_ref()
            .is_some_and(|attempt| attempt.expires_at <= now)
        {
            *slot = None;
        }
    }

    /// Waits until the attempt finishes, or until [timeout] elapses.
    pub async fn wait(
        receiver: oneshot::Receiver<PairOutcome>,
        timeout: Duration,
    ) -> Option<PairOutcome> {
        tokio::time::timeout(timeout, receiver).await.ok()?.ok()
    }
}

pub fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};

    fn store_path() -> PathBuf {
        static COUNTER: AtomicU64 = AtomicU64::new(0);
        std::env::temp_dir()
            .join(format!(
                "voicebridge-pairing-{}-{}",
                std::process::id(),
                COUNTER.fetch_add(1, Ordering::Relaxed)
            ))
            .join("devices.json")
    }

    fn manager(path: PathBuf, clock: u64) -> (PairingManager, Arc<AsyncMutex<DeviceStore>>) {
        let devices = Arc::new(AsyncMutex::new(DeviceStore::load(path).unwrap()));
        let ticks = Arc::new(AtomicU64::new(clock));
        let manager = PairingManager::with_clock(
            devices.clone(),
            "windows-1".to_string(),
            "DESKTOP".to_string(),
            Box::new(move || ticks.load(Ordering::Relaxed)),
        );
        (manager, devices)
    }

    #[tokio::test]
    async fn local_approval_after_client_confirm_pairs_the_device() {
        let path = store_path();
        let (manager, devices) = manager(path.clone(), 1_000);
        let challenge = manager.begin("android-1", "Pixel").unwrap();
        // The client confirms first and waits.
        let waiting = manager.client_confirm(&challenge.pairing_id, true).await;
        assert!(matches!(waiting, ClientConfirm::Waiting(_)));
        let receiver = match waiting {
            ClientConfirm::Waiting(receiver) => receiver,
            _ => unreachable!(),
        };
        manager.local_decision(true).await.unwrap();
        let outcome = PairingManager::wait(receiver, Duration::from_secs(1))
            .await
            .expect("outcome");
        match outcome {
            PairOutcome::Paired { token } => {
                assert!(devices.lock().await.is_authorized("android-1", &token));
            }
            other => panic!("unexpected outcome: {other:?}"),
        }
        let _ = std::fs::remove_dir_all(path.parent().unwrap());
    }

    #[tokio::test]
    async fn local_approval_first_then_client_confirm_completes_immediately() {
        let path = store_path();
        let (manager, _) = manager(path.clone(), 1_000);
        let challenge = manager.begin("android-1", "Pixel").unwrap();
        manager.local_decision(true).await.unwrap();
        match manager.client_confirm(&challenge.pairing_id, true).await {
            ClientConfirm::Complete(PairOutcome::Paired { token }) => {
                assert_eq!(token.len(), 43);
            }
            _ => panic!("expected an immediate pairing"),
        }
        assert!(manager.pending().is_none());
        let _ = std::fs::remove_dir_all(path.parent().unwrap());
    }

    #[tokio::test]
    async fn local_denial_rejects_even_after_client_confirm() {
        let path = store_path();
        let (manager, devices) = manager(path.clone(), 1_000);
        let challenge = manager.begin("android-1", "Pixel").unwrap();
        manager.local_decision(false).await.unwrap();
        match manager.client_confirm(&challenge.pairing_id, true).await {
            ClientConfirm::Complete(PairOutcome::Rejected) => {}
            _ => panic!("expected rejection"),
        }
        assert!(devices.lock().await.list().is_empty());
        let _ = std::fs::remove_dir_all(path.parent().unwrap());
    }

    #[tokio::test]
    async fn only_one_attempt_can_be_open() {
        let path = store_path();
        let (manager, _) = manager(path.clone(), 1_000);
        manager.begin("android-1", "Pixel").unwrap();
        assert_eq!(manager.begin("android-2", "Other"), Err(PairError::Busy));
        let _ = std::fs::remove_dir_all(path.parent().unwrap());
    }

    #[tokio::test]
    async fn expired_attempts_disappear_and_cannot_be_confirmed() {
        let path = store_path();
        let devices = Arc::new(AsyncMutex::new(DeviceStore::load(path.clone()).unwrap()));
        let ticks = Arc::new(AtomicU64::new(1_000));
        let clock = {
            let ticks = ticks.clone();
            Box::new(move || ticks.load(Ordering::Relaxed)) as Clock
        };
        let manager = PairingManager::with_clock(
            devices,
            "windows-1".to_string(),
            "DESKTOP".to_string(),
            clock,
        );
        let challenge = manager.begin("android-1", "Pixel").unwrap();
        ticks.store(1_000 + PAIRING_TTL_MS + 1, Ordering::Relaxed);
        assert!(manager.pending().is_none());
        assert!(matches!(
            manager.client_confirm(&challenge.pairing_id, true).await,
            ClientConfirm::Stale
        ));
        let _ = std::fs::remove_dir_all(path.parent().unwrap());
    }

    #[tokio::test]
    async fn rate_limit_blocks_a_flood_of_requests() {
        let path = store_path();
        let (manager, _) = manager(path.clone(), 1_000);
        for _ in 0..MAX_ATTEMPTS {
            let challenge = manager
                .begin("android-1", "Pixel")
                .expect("attempt allowed");
            // Clear the slot so the rate limit, not the single-slot rule, is exercised.
            manager.expire(&challenge.pairing_id);
        }
        assert_eq!(manager.begin("android-1", "Pixel"), Err(PairError::Busy));
        let _ = std::fs::remove_dir_all(path.parent().unwrap());
    }

    #[tokio::test]
    async fn confirming_an_unknown_attempt_is_stale() {
        let path = store_path();
        let (manager, _) = manager(path.clone(), 1_000);
        assert!(matches!(
            manager.client_confirm("missing", true).await,
            ClientConfirm::Stale
        ));
        let _ = std::fs::remove_dir_all(path.parent().unwrap());
    }
}
