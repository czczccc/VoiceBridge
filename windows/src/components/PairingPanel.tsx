import { useCallback, useEffect, useState } from "react";

import { listDevices, revokeDevice, type PairedDevice } from "../api/server";
import { usePairing } from "../state/usePairing";

function describe(cause: unknown): string {
  if (typeof cause === "string") return cause;
  if (cause instanceof Error) return cause.message;
  return "操作失败，请重试";
}

/**
 * Pairing is approved by the local user, never by the phone: a request shows up
 * here and only an explicit Allow issues an access token.
 */
export function PairingPanel() {
  const { pending, busy, decide } = usePairing(true);
  const [devices, setDevices] = useState<PairedDevice[]>([]);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setDevices(await listDevices());
      setError(null);
    } catch (cause) {
      setError(describe(cause));
    }
  }, []);

  // Refresh once per distinct request, not on every poll tick.
  const pendingDeviceId = pending?.deviceId ?? null;
  useEffect(() => {
    void refresh();
  }, [refresh, pendingDeviceId]);

  const revoke = async (deviceId: string) => {
    try {
      await revokeDevice(deviceId);
      await refresh();
    } catch (cause) {
      setError(describe(cause));
    }
  };

  return (
    <section aria-label="设备配对">
      <h2>设备配对</h2>
      {pending ? (
        <div className="pairing-request" role="alert">
          <p>
            <strong>{pending.deviceName}</strong> 请求配对
          </p>
          <p className="hint">
            只在这是你自己的手机时允许。允许后该手机可以向本机发送输入文本。
          </p>
          <p className="hint">
            请求将在 {new Date(pending.expiresAt).toLocaleTimeString()} 失效
          </p>
          <div className="actions">
            <button
              type="button"
              onClick={() => void decide(true)}
              disabled={busy}
            >
              允许
            </button>
            <button
              type="button"
              onClick={() => void decide(false)}
              disabled={busy}
            >
              拒绝
            </button>
          </div>
        </div>
      ) : (
        <p className="hint">
          在手机 App 中选择这台电脑即可发起配对。没有请求时无需操作。
        </p>
      )}
      {error ? (
        <p className="error" role="alert">
          {error}
        </p>
      ) : null}
      <h3>已配对设备</h3>
      {devices.length === 0 ? (
        <p className="hint">还没有已配对的设备。</p>
      ) : (
        <ul className="devices">
          {devices.map((device) => (
            <li key={device.deviceId}>
              <span>{device.name}</span>
              <button
                type="button"
                onClick={() => void revoke(device.deviceId)}
              >
                撤销
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
