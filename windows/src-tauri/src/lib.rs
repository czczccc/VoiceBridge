pub mod protocol;

pub fn run() {
    tauri::Builder::default()
        .run(tauri::generate_context!())
        .expect("VoiceBridge failed to start");
}
