/// How text actually reaches the focused control. Keeping this behind a trait lets the
/// session rules be tested without touching a real desktop, and leaves room for a
/// clipboard-based backend for large payloads later.
pub trait TextInjectionBackend: Send + Sync {
    /// Deletes [count] characters before the caret.
    fn backspace(&self, count: usize) -> Result<(), InjectError>;
    /// Types [text] as Unicode input.
    fn type_text(&self, text: &str) -> Result<(), InjectError>;
}

#[derive(Debug, PartialEq, Eq)]
pub enum InjectError {
    /// The platform backend is unavailable (non-Windows builds).
    Unsupported,
    /// The OS rejected or partially consumed the input events.
    Failed,
}

/// Types characters as Unicode key events, which works in ordinary Win32 and browser
/// text fields without touching the clipboard.
pub struct SendInputBackend;

#[cfg(windows)]
mod platform {
    use super::{InjectError, SendInputBackend, TextInjectionBackend};
    use std::mem::size_of;
    use windows::Win32::UI::Input::KeyboardAndMouse::{
        SendInput, INPUT, INPUT_0, INPUT_KEYBOARD, KEYBDINPUT, KEYBD_EVENT_FLAGS, KEYEVENTF_KEYUP,
        KEYEVENTF_UNICODE, VIRTUAL_KEY,
    };

    /// Longest batch handed to one SendInput call.
    const CHUNK: usize = 256;

    fn key(vk: VIRTUAL_KEY, scan: u16, flags: KEYBD_EVENT_FLAGS) -> INPUT {
        INPUT {
            r#type: INPUT_KEYBOARD,
            Anonymous: INPUT_0 {
                ki: KEYBDINPUT {
                    wVk: vk,
                    wScan: scan,
                    dwFlags: flags,
                    time: 0,
                    dwExtraInfo: 0,
                },
            },
        }
    }

    fn key_up() -> KEYBD_EVENT_FLAGS {
        KEYEVENTF_KEYUP
    }

    fn unicode_with_key_up() -> KEYBD_EVENT_FLAGS {
        KEYBD_EVENT_FLAGS(KEYEVENTF_UNICODE.0 | KEYEVENTF_KEYUP.0)
    }

    /// UTF-16 code units of `text`, one down/up pair each.
    ///
    /// Line breaks become spaces, never Enter: remote text must not send a chat message or submit
    /// a form, so pressing Enter on the PC stays the user's own decision. Mapping each UTF-16 unit
    /// to exactly one typed character also keeps the diff's backspace count valid.
    fn events_for(text: &str) -> Vec<INPUT> {
        let mut events = Vec::with_capacity(text.len() * 2);
        for unit in text.encode_utf16() {
            let unit = if unit == u16::from(b'\r') || unit == u16::from(b'\n') {
                u16::from(b' ')
            } else {
                unit
            };
            events.push(key(VIRTUAL_KEY(0), unit, KEYEVENTF_UNICODE));
            events.push(key(VIRTUAL_KEY(0), unit, unicode_with_key_up()));
        }
        events
    }

    fn send(events: &[INPUT]) -> Result<(), InjectError> {
        for chunk in events.chunks(CHUNK) {
            // SAFETY: the slice lives for the call and its length is passed explicitly.
            let sent = unsafe { SendInput(chunk, size_of::<INPUT>() as i32) };
            if sent as usize != chunk.len() {
                return Err(InjectError::Failed);
            }
        }
        Ok(())
    }

    impl TextInjectionBackend for SendInputBackend {
        fn backspace(&self, count: usize) -> Result<(), InjectError> {
            if count == 0 {
                return Ok(());
            }
            let mut events = Vec::with_capacity(count * 2);
            for _ in 0..count {
                events.push(key(
                    windows::Win32::UI::Input::KeyboardAndMouse::VK_BACK,
                    0,
                    KEYBD_EVENT_FLAGS(0),
                ));
                events.push(key(
                    windows::Win32::UI::Input::KeyboardAndMouse::VK_BACK,
                    0,
                    key_up(),
                ));
            }
            send(&events)
        }

        fn type_text(&self, text: &str) -> Result<(), InjectError> {
            if text.is_empty() {
                return Ok(());
            }
            send(&events_for(text))
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn every_code_unit_produces_a_down_and_an_up_event() {
            assert_eq!(events_for("ab").len(), 4);
            // A non-BMP emoji is two UTF-16 code units, so four events.
            assert_eq!(events_for("😀").len(), 4);
        }

        #[test]
        fn line_breaks_become_spaces_and_never_enter() {
            // Each unit still produces a down/up pair: one line feed is one space.
            assert_eq!(events_for("\n").len(), 2);
            // CRLF is two units, so two spaces; this keeps backspace accounting exact.
            assert_eq!(events_for("\r\n").len(), 4);
            assert_eq!(events_for("a\nb").len(), 6);
            let all_unicode = events_for("a\r\nb\nc")
                .iter()
                .all(|event| unsafe { event.Anonymous.ki.wVk == VIRTUAL_KEY(0) });
            assert!(all_unicode);
        }

        #[test]
        fn empty_text_sends_nothing() {
            assert!(events_for("").is_empty());
        }
    }
}

#[cfg(not(windows))]
impl TextInjectionBackend for SendInputBackend {
    fn backspace(&self, _count: usize) -> Result<(), InjectError> {
        Err(InjectError::Unsupported)
    }

    fn type_text(&self, _text: &str) -> Result<(), InjectError> {
        Err(InjectError::Unsupported)
    }
}
