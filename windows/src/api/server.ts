import { invoke } from "@tauri-apps/api/core";

export interface ServerStatus {
  running: boolean;
  address: string | null;
  port: number | null;
  lanSupported: boolean;
  fingerprint: string | null;
}

export interface PairedDevice {
  deviceId: string;
  name: string;
  lastSeen: number;
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

export function getLocalFingerprint(): Promise<string | null> {
  return invoke<string | null>("local_fingerprint");
}

export function listDevices(): Promise<PairedDevice[]> {
  return invoke<PairedDevice[]>("list_devices");
}

export function revokeDevice(deviceId: string): Promise<boolean> {
  return invoke<boolean>("revoke_device", { deviceId });
}
