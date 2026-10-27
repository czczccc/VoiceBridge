use std::sync::Arc;

use tauri::{Manager, State};

pub mod discovery;
pub mod identity;
pub mod input;
pub mod pairing;
pub mod protocol;
pub mod server;
pub mod session;

use identity::Identity;
use pairing::devices::{Device, DeviceStore};
use pairing::session::PendingPairing;
use server::{Server, Status};
use session::SessionInfo;

/// What the UI may see. The stored token digest never leaves the backend.
#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct DeviceSummary {
    device_id: String,
    name: String,
    last_seen: u64,
}

impl From<Device> for DeviceSummary {
    fn from(device: Device) -> Self {
        Self {
            device_id: device.device_id,
            name: device.name,
            last_seen: device.last_seen,
        }
    }
}

#[tauri::command]
async fn server_status(server: State<'_, Arc<Server>>) -> Result<Status, String> {
    Ok(server.status().await)
}

#[tauri::command]
async fn start_server(server: State<'_, Arc<Server>>, allow_lan: bool) -> Result<Status, String> {
    server.start(allow_lan).await
}

#[tauri::command]
async fn stop_server(server: State<'_, Arc<Server>>) -> Result<Status, String> {
    Ok(server.stop().await)
}

/// Safe to display: it is the public half of the local identity.
#[tauri::command]
async fn local_fingerprint(server: State<'_, Arc<Server>>) -> Result<Option<String>, String> {
    Ok(server.fingerprint())
}

#[tauri::command]
async fn list_devices(server: State<'_, Arc<Server>>) -> Result<Vec<DeviceSummary>, String> {
    Ok(server
        .list_devices()
        .await
        .into_iter()
        .map(DeviceSummary::from)
        .collect())
}

#[tauri::command]
async fn revoke_device(server: State<'_, Arc<Server>>, device_id: String) -> Result<bool, String> {
    server.revoke_device(&device_id).await
}

/// The pairing request the local user has to approve, if one is waiting.
#[tauri::command]
async fn pairing_pending(server: State<'_, Arc<Server>>) -> Result<Option<PendingPairing>, String> {
    Ok(server.pending_pairing())
}

/// Records the local Allow/Deny decision. Never accepts a token from the caller.
#[tauri::command]
async fn pairing_decide(server: State<'_, Arc<Server>>, accept: bool) -> Result<(), String> {
    server.decide_pairing(accept).await
}

#[tauri::command]
async fn input_session_status(
    server: State<'_, Arc<Server>>,
) -> Result<Option<SessionInfo>, String> {
    Ok(server.session_info())
}

/// Waits three seconds, then captures whatever window is in front and arms a session.
#[tauri::command]
async fn start_input_session(server: State<'_, Arc<Server>>) -> Result<SessionInfo, String> {
    server.start_session().await
}

#[tauri::command]
async fn stop_input_session(server: State<'_, Arc<Server>>) -> Result<Option<String>, String> {
    Ok(server.stop_session())
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_process::init())
        .setup(|app| {
            let directory = app.path().app_config_dir()?;
            let store_path = directory.join("devices.json");
            let devices = DeviceStore::load(store_path.clone()).unwrap_or_else(|error| {
                // A damaged file must not fail open, but it also must not block the UI.
                // Starting empty only removes authorization; it grants nothing.
                eprintln!("voicebridge devices rejected kind={:?}", error.kind());
                DeviceStore::empty(store_path)
            });
            let server = Arc::new(Server::new(server::log::stderr(), devices));
            match Identity::load_or_create(&directory) {
                Ok(identity) => server.set_identity(identity),
                Err(error) => eprintln!("voicebridge identity unavailable: {error}"),
            }
            app.manage(server);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            server_status,
            start_server,
            stop_server,
            local_fingerprint,
            list_devices,
            revoke_device,
            pairing_pending,
            pairing_decide,
            input_session_status,
            start_input_session,
            stop_input_session
        ])
        .run(tauri::generate_context!())
        .expect("VoiceBridge failed to start");
}
