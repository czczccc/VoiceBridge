use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use crate::input::backend::{InjectError, TextInjectionBackend};
use crate::input::diff;
use crate::input::focus::{FocusProbe, WindowTarget};
use crate::protocol::{AckStatus, SessionState, TextRange};

/// What the UI shows for the current session. `state` is a stable lowercase label so
/// the protocol enum does not need a second serialization.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionInfo {
    pub session_id: String,
    pub target_label: String,
    pub state: String,
}

fn state_label(state: SessionState) -> &'static str {
    match state {
        SessionState::Idle => "idle",
        SessionState::Armed => "armed",
        SessionState::Active => "active",
        SessionState::PausedFocus => "paused-focus",
        SessionState::Stopping => "stopping",
        SessionState::Error => "error",
    }
}

struct Active {
    id: String,
    target: WindowTarget,
    state: SessionState,
    bound_device: Option<String>,
    last_seq: u64,
    last_text: String,
}

/// Owns the single input session.
///
/// A snapshot is only applied when the session is still the one on screen, the sequence
/// advanced, the peer is the device that started writing, and the foreground window is
/// still the target captured at start. Any doubt pauses the session instead of injecting.
pub struct SessionManager {
    inner: Mutex<Option<Active>>,
    backend: Arc<dyn TextInjectionBackend>,
    focus: Arc<dyn FocusProbe>,
    next: AtomicU64,
}

impl SessionManager {
    pub fn new(backend: Arc<dyn TextInjectionBackend>, focus: Arc<dyn FocusProbe>) -> Self {
        Self {
            inner: Mutex::new(None),
            backend,
            focus,
            next: AtomicU64::new(1),
        }
    }

    /// Arms a session for [target]. Replacing an existing session stops it first.
    pub fn start(&self, target: WindowTarget) -> SessionInfo {
        let number = self.next.fetch_add(1, Ordering::Relaxed);
        let info = SessionInfo {
            session_id: format!("session-{number}"),
            target_label: target.label(),
            state: state_label(SessionState::Armed).to_string(),
        };
        let active = Active {
            id: info.session_id.clone(),
            target,
            state: SessionState::Armed,
            bound_device: None,
            last_seq: 0,
            last_text: String::new(),
        };
        if let Ok(mut slot) = self.inner.lock() {
            *slot = Some(active);
        }
        info
    }

    /// Stops the session and returns its id so callers can broadcast `session.stop`.
    pub fn stop(&self) -> Option<String> {
        let mut slot = self.inner.lock().ok()?;
        let id = slot.as_ref().map(|active| active.id.clone());
        if let Some(active) = slot.as_mut() {
            active.state = SessionState::Stopping;
        }
        *slot = None;
        id
    }

    /// The window currently in front, used to arm a session.
    pub fn foreground(&self) -> Option<WindowTarget> {
        self.focus.foreground()
    }

    /// The device that first wrote into the session, if any.
    pub fn bound_device(&self) -> Option<String> {
        self.inner.lock().ok()?.as_ref()?.bound_device.clone()
    }

    pub fn info(&self) -> Option<SessionInfo> {
        let slot = self.inner.lock().ok()?;
        slot.as_ref().map(|active| SessionInfo {
            session_id: active.id.clone(),
            target_label: active.target.label(),
            state: state_label(active.state).to_string(),
        })
    }

    /// Applies one snapshot. Returns the status to put in `text.ack`.
    pub fn apply(
        &self,
        device_id: &str,
        session_id: &str,
        seq: u64,
        text: &str,
        selection: Option<TextRange>,
    ) -> AckStatus {
        let Ok(mut slot) = self.inner.lock() else {
            return AckStatus::InjectionError;
        };
        let Some(active) = slot.as_mut() else {
            return AckStatus::RejectedSession;
        };
        if active.id != session_id {
            return AckStatus::RejectedSession;
        }
        match &active.bound_device {
            Some(bound) if bound != device_id => return AckStatus::RejectedSession,
            None => active.bound_device = Some(device_id.to_string()),
            _ => {}
        }
        // A paused or failed session is never resumed automatically.
        if matches!(
            active.state,
            SessionState::PausedFocus | SessionState::Error
        ) {
            return AckStatus::PausedFocus;
        }
        if seq <= active.last_seq {
            return AckStatus::Stale;
        }
        // A selection means the user is editing by hand, which the MVP does not track.
        if selection.is_some_and(|range| range.start != range.end) {
            active.state = SessionState::PausedFocus;
            return AckStatus::PausedFocus;
        }
        let Some(current) = self.focus.foreground() else {
            active.state = SessionState::PausedFocus;
            return AckStatus::PausedFocus;
        };
        if current.handle != active.target.handle {
            active.state = SessionState::PausedFocus;
            return AckStatus::PausedFocus;
        }

        let delta = diff::delta(&active.last_text, text);
        if self.backend.backspace(delta.backspaces).is_err() {
            active.state = SessionState::Error;
            return AckStatus::InjectionError;
        }
        if self.backend.type_text(&delta.suffix).is_err() {
            active.state = SessionState::Error;
            return AckStatus::InjectionError;
        }
        active.last_text = text.to_string();
        active.last_seq = seq;
        active.state = SessionState::Active;
        AckStatus::Applied
    }
}

/// Backend/`FocusProbe` pair used in production. Constructed once and shared by the
/// server and the Tauri commands.
pub fn defaults() -> (Arc<dyn TextInjectionBackend>, Arc<dyn FocusProbe>) {
    (
        Arc::new(crate::input::backend::SendInputBackend),
        Arc::new(crate::input::focus::SystemFocus),
    )
}

impl std::fmt::Display for InjectError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Unsupported => formatter.write_str("当前平台不支持文本注入"),
            Self::Failed => formatter.write_str("系统拒绝了文本注入"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex as StdMutex;

    #[derive(Default)]
    struct RecordingBackend {
        operations: StdMutex<Vec<String>>,
        fail: bool,
    }

    impl TextInjectionBackend for RecordingBackend {
        fn backspace(&self, count: usize) -> Result<(), InjectError> {
            if self.fail {
                return Err(InjectError::Failed);
            }
            self.operations
                .lock()
                .unwrap()
                .push(format!("backspace:{count}"));
            Ok(())
        }

        fn type_text(&self, text: &str) -> Result<(), InjectError> {
            if self.fail {
                return Err(InjectError::Failed);
            }
            self.operations.lock().unwrap().push(format!("type:{text}"));
            Ok(())
        }
    }

    struct FixedFocus {
        target: StdMutex<Option<WindowTarget>>,
    }

    impl FocusProbe for FixedFocus {
        fn foreground(&self) -> Option<WindowTarget> {
            self.target.lock().unwrap().clone()
        }
    }

    fn target(handle: isize) -> WindowTarget {
        WindowTarget {
            handle,
            process_id: 7,
            title: "Notepad".to_string(),
        }
    }

    struct Fixture {
        manager: SessionManager,
        backend: Arc<RecordingBackend>,
        focus: Arc<FixedFocus>,
    }

    fn fixture() -> Fixture {
        let backend = Arc::new(RecordingBackend::default());
        let focus = Arc::new(FixedFocus {
            target: StdMutex::new(Some(target(1))),
        });
        Fixture {
            manager: SessionManager::new(backend.clone(), focus.clone()),
            backend,
            focus,
        }
    }

    fn ops(fixture: &Fixture) -> Vec<String> {
        fixture.backend.operations.lock().unwrap().clone()
    }

    #[test]
    fn a_session_starts_armed() {
        let fixture = fixture();
        let info = fixture.manager.start(target(1));
        assert_eq!(info.state, "armed");
        assert_eq!(fixture.manager.info().unwrap().session_id, info.session_id);
    }

    #[test]
    fn a_snapshot_is_applied_and_advances_the_sequence() {
        let fixture = fixture();
        let info = fixture.manager.start(target(1));
        assert_eq!(
            fixture
                .manager
                .apply("android-1", &info.session_id, 1, "你好", None),
            AckStatus::Applied
        );
        assert_eq!(ops(&fixture), vec!["backspace:0", "type:你好"]);
        assert_eq!(fixture.manager.info().unwrap().state, "active");

        // Editing the tail deletes only what changed.
        assert_eq!(
            fixture
                .manager
                .apply("android-1", &info.session_id, 2, "你好世界", None),
            AckStatus::Applied
        );
        assert_eq!(
            ops(&fixture),
            vec!["backspace:0", "type:你好", "backspace:0", "type:世界"]
        );
    }

    #[test]
    fn a_replayed_sequence_is_stale_and_not_injected() {
        let fixture = fixture();
        let info = fixture.manager.start(target(1));
        fixture
            .manager
            .apply("android-1", &info.session_id, 2, "a", None);
        let before = ops(&fixture);
        assert_eq!(
            fixture
                .manager
                .apply("android-1", &info.session_id, 2, "b", None),
            AckStatus::Stale
        );
        assert_eq!(ops(&fixture), before);
    }

    #[test]
    fn an_unknown_or_stopped_session_is_rejected() {
        let fixture = fixture();
        assert_eq!(
            fixture
                .manager
                .apply("android-1", "session-999", 1, "x", None),
            AckStatus::RejectedSession
        );
        let info = fixture.manager.start(target(1));
        fixture.manager.stop();
        assert_eq!(
            fixture
                .manager
                .apply("android-1", &info.session_id, 1, "x", None),
            AckStatus::RejectedSession
        );
        assert!(fixture.manager.info().is_none());
    }

    #[test]
    fn another_device_cannot_write_into_the_session() {
        let fixture = fixture();
        let info = fixture.manager.start(target(1));
        assert_eq!(
            fixture
                .manager
                .apply("android-1", &info.session_id, 1, "a", None),
            AckStatus::Applied
        );
        assert_eq!(
            fixture
                .manager
                .apply("android-2", &info.session_id, 2, "b", None),
            AckStatus::RejectedSession
        );
    }

    #[test]
    fn a_focus_change_pauses_and_never_injects_into_the_new_window() {
        let fixture = fixture();
        let info = fixture.manager.start(target(1));
        *fixture.focus.target.lock().unwrap() = Some(target(2));
        let before = ops(&fixture);
        assert_eq!(
            fixture
                .manager
                .apply("android-1", &info.session_id, 1, "secret", None),
            AckStatus::PausedFocus
        );
        assert_eq!(ops(&fixture), before);
        // It stays paused even if focus returns: the user must restart the session.
        *fixture.focus.target.lock().unwrap() = Some(target(1));
        assert_eq!(
            fixture
                .manager
                .apply("android-1", &info.session_id, 2, "secret", None),
            AckStatus::PausedFocus
        );
    }

    #[test]
    fn a_missing_foreground_window_pauses() {
        let fixture = fixture();
        let info = fixture.manager.start(target(1));
        *fixture.focus.target.lock().unwrap() = None;
        assert_eq!(
            fixture
                .manager
                .apply("android-1", &info.session_id, 1, "a", None),
            AckStatus::PausedFocus
        );
    }

    #[test]
    fn a_non_collapsed_selection_pauses() {
        let fixture = fixture();
        let info = fixture.manager.start(target(1));
        let selection = TextRange { start: 0, end: 2 };
        assert_eq!(
            fixture
                .manager
                .apply("android-1", &info.session_id, 1, "abc", Some(selection)),
            AckStatus::PausedFocus
        );
    }

    #[test]
    fn a_backend_failure_reports_an_injection_error_and_stays_failed() {
        let backend = Arc::new(RecordingBackend {
            operations: StdMutex::new(Vec::new()),
            fail: true,
        });
        let focus = Arc::new(FixedFocus {
            target: StdMutex::new(Some(target(1))),
        });
        let manager = SessionManager::new(backend.clone(), focus);
        let info = manager.start(target(1));
        assert_eq!(
            manager.apply("android-1", &info.session_id, 1, "a", None),
            AckStatus::InjectionError
        );
        assert_eq!(manager.info().unwrap().state, "error");
    }
}
