import { invoke } from "@tauri-apps/api/core";

export interface ServerStatus {
  running: boolean;
  address: string | null;
  port: number | null;
  lanSupported: boolean;
}

export function getServerStatus(): Promise<ServerStatus> {
  return invoke<ServerStatus>("server_status");
}

export function startServer(allowLan: boolean): Promise<ServerStatus> {
  return invoke<ServerStatus>("start_server", { allowLan });
}

export function stopServer(): Promise<ServerStatus> {
  return invoke<ServerStatus>("stop_server");
}
