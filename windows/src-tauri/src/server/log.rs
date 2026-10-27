use std::sync::Arc;

/// Structured diagnostics without message bodies, clipboard content, or credentials.
///
/// Callers may only pass static event names plus addresses, ports, counters and error
/// codes. Implementations must not add payload data on their own.
pub trait LogSink: Send + Sync + 'static {
    fn record(&self, line: &str);
}

#[derive(Debug, Default)]
pub struct StderrSink;

impl LogSink for StderrSink {
    fn record(&self, line: &str) {
        eprintln!("voicebridge server {line}");
    }
}

#[derive(Debug, Default)]
pub struct MemorySink {
    lines: std::sync::Mutex<Vec<String>>,
}

impl MemorySink {
    pub fn lines(&self) -> Vec<String> {
        self.lines
            .lock()
            .map(|lines| lines.clone())
            .unwrap_or_default()
    }
}

impl LogSink for MemorySink {
    fn record(&self, line: &str) {
        if let Ok(mut lines) = self.lines.lock() {
            lines.push(line.to_string());
        }
    }
}

pub fn stderr() -> Arc<dyn LogSink> {
    Arc::new(StderrSink)
}
