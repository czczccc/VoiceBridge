/// The window the user pointed at when the input session started.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct WindowTarget {
    /// Native window handle as an integer so this type stays plain data.
    pub handle: isize,
    pub process_id: u32,
    pub title: String,
}

impl WindowTarget {
    pub fn label(&self) -> String {
        if self.title.trim().is_empty() {
            format!("窗口 {}", self.handle)
        } else {
            self.title.clone()
        }
    }
}

/// Reads the current foreground window. Injectable so session rules can be tested
/// without a real desktop.
pub trait FocusProbe: Send + Sync {
    fn foreground(&self) -> Option<WindowTarget>;
}

pub struct SystemFocus;

impl FocusProbe for SystemFocus {
    fn foreground(&self) -> Option<WindowTarget> {
        platform::foreground()
    }
}

#[cfg(windows)]
mod platform {
    use super::WindowTarget;
    use windows::Win32::Foundation::HWND;
    use windows::Win32::UI::WindowsAndMessaging::{
        GetForegroundWindow, GetWindowTextW, GetWindowThreadProcessId, IsWindow,
    };

    pub fn foreground() -> Option<WindowTarget> {
        // SAFETY: all calls take plain window handles; the text buffer is owned here and
        // GetWindowTextW is told its exact length.
        unsafe {
            let window: HWND = GetForegroundWindow();
            if window.is_invalid() || !IsWindow(Some(window)).as_bool() {
                return None;
            }
            let mut process_id = 0u32;
            GetWindowThreadProcessId(window, Some(&mut process_id));
            let mut buffer = [0u16; 256];
            let length = GetWindowTextW(window, &mut buffer);
            let title = if length > 0 {
                String::from_utf16_lossy(&buffer[..length as usize])
            } else {
                String::new()
            };
            Some(WindowTarget {
                handle: window.0 as isize,
                process_id,
                title,
            })
        }
    }
}

#[cfg(not(windows))]
mod platform {
    use super::WindowTarget;

    pub fn foreground() -> Option<WindowTarget> {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_titled_window_uses_its_title() {
        let target = WindowTarget {
            handle: 1,
            process_id: 2,
            title: "Notepad".to_string(),
        };
        assert_eq!(target.label(), "Notepad");
    }

    #[test]
    fn an_untitled_window_falls_back_to_its_handle() {
        let target = WindowTarget {
            handle: 42,
            process_id: 2,
            title: "   ".to_string(),
        };
        assert_eq!(target.label(), "窗口 42");
    }
}
