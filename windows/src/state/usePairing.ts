import { useCallback, useEffect, useState } from "react";

import {
  decidePairing,
  getPendingPairing,
  type PendingPairing,
} from "../api/pairing";

const POLL_MS = 1000;

/**
 * Polls for a pending pairing request while the service runs. A poll keeps the
 * Tauri surface small: no event plumbing, and a stopped service simply returns
 * null instead of buffering stale requests.
 */
export function usePairing(enabled: boolean) {
  const [pending, setPending] = useState<PendingPairing | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!enabled) {
      setPending(null);
      return;
    }
    // One poll chain per effect run; `cancelled` keeps an in-flight response
    // from a previous run from scheduling a second chain.
    let cancelled = false;
    let timer: number | undefined;
    const tick = async () => {
      try {
        const next = await getPendingPairing();
        if (!cancelled) setPending(next);
      } catch {
        // The service may be stopping; keep polling quietly.
      }
      if (!cancelled) timer = window.setTimeout(tick, POLL_MS);
    };
    void tick();
    return () => {
      cancelled = true;
      if (timer) window.clearTimeout(timer);
    };
  }, [enabled]);

  const decide = useCallback(async (accept: boolean) => {
    setBusy(true);
    try {
      await decidePairing(accept);
      setPending(null);
    } finally {
      setBusy(false);
    }
  }, []);

  return { pending, busy, decide };
}
