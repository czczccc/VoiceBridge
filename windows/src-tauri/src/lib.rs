use std::sync::Arc;

use tauri::State;

pub mod protocol;
pub mod server;

use server::{Server, Status};

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

pub fn run() {
    let server = Arc::new(Server::new(server::log::stderr()));
    tauri::Builder::default()
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_process::init())
        .manage(server)
        .invoke_handler(tauri::generate_handler![
            server_status,
            start_server,
            stop_server
        ])
        .run(tauri::generate_context!())
        .expect("VoiceBridge failed to start");
}
