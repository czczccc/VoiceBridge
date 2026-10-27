import { invoke } from "@tauri-apps/api/core";

export interface PendingPairing {
  deviceId: string;
  deviceName: string;
  expiresAt: number;
}

/** The request the local user has to approve, if one is waiting. */
export function getPendingPairing(): Promise<PendingPairing | null> {
  return invoke<PendingPairing | null>("pairing_pending");
}

/** The backend issues the token; the UI only says yes or no. */
export function decidePairing(accept: boolean): Promise<void> {
  return invoke<void>("pairing_decide", { accept });
}
