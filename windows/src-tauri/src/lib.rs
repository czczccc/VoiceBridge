use std::sync::Arc;
use std::time::Duration;

use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
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

#[tauri::command]
async fn auto_sync_status(server: State<'_, Arc<Server>>) -> Result<bool, String> {
    Ok(server.auto_sync())
}

/// Turns phone-initiated sessions on or off; disabling also stops the current session.
#[tauri::command]
async fn set_auto_sync(server: State<'_, Arc<Server>>, enabled: bool) -> Result<bool, String> {
    Ok(server.set_auto_sync(enabled))
}

#[tauri::command]
async fn stop_input_session(server: State<'_, Arc<Server>>) -> Result<Option<String>, String> {
    Ok(server.stop_session())
}

const TRAY_ID: &str = "voicebridge-tray";
const TRAY_SHOW: &str = "tray-show";
const TRAY_QUIT: &str = "tray-quit";

/// Hiding instead of closing keeps the service and any active input session
/// running, so the window has to stay reachable from the tray.
fn restore_main_window(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

/// The only path that really quits: leaving the listener or an armed input
/// session behind after the UI is gone would keep writing to the target window.
fn quit_application(app: &tauri::AppHandle, server: Arc<Server>) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        server.stop_session();
        // A stuck connection must not keep the process alive after the user quit.
        let _ = tokio::time::timeout(Duration::from_secs(3), server.stop()).await;
        app.exit(0);
    });
}

fn install_tray(app: &tauri::App, server: Arc<Server>) -> tauri::Result<()> {
    let show = MenuItem::with_id(app, TRAY_SHOW, "显示主窗口", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, TRAY_QUIT, "退出 VoiceBridge", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&show, &quit])?;

    let mut tray = TrayIconBuilder::with_id(TRAY_ID)
        .tooltip("VoiceBridge")
        .menu(&menu)
        // Left click restores the window; the menu is on right click.
        .show_menu_on_left_click(false)
        .on_menu_event(move |app, event| match event.id().as_ref() {
            TRAY_SHOW => restore_main_window(app),
            TRAY_QUIT => quit_application(app, server.clone()),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                restore_main_window(tray.app_handle());
            }
        });

    if let Some(icon) = app.default_window_icon() {
        tray = tray.icon(icon.clone());
    }
    tray.build(app)?;
    Ok(())
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
            app.manage(server.clone());
            install_tray(app, server)?;
            if let Some(window) = app.get_webview_window("main") {
                let window_for_close = window.clone();
                window.on_window_event(move |event| {
                    if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                        // The X button hides the window; quitting is a tray action.
                        api.prevent_close();
                        let _ = window_for_close.hide();
                    }
                });
            }
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
            auto_sync_status,
            set_auto_sync,
            stop_input_session
        ])
        .run(tauri::generate_context!())
        .expect("VoiceBridge failed to start");
}
