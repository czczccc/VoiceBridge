import { useCallback, useEffect, useState } from "react";

import {
  getServerStatus,
  startServer,
  stopServer,
  type ServerStatus,
} from "../api/server";

function describe(cause: unknown): string {
  if (typeof cause === "string") return cause;
  if (cause instanceof Error) return cause.message;
  return "操作失败，请重试";
}

export function useServer() {
  const [status, setStatus] = useState<ServerStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setStatus(await getServerStatus());
      setError(null);
    } catch (cause) {
      setError(describe(cause));
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const run = useCallback(async (action: () => Promise<ServerStatus>) => {
    setBusy(true);
    try {
      setStatus(await action());
      setError(null);
    } catch (cause) {
      setError(describe(cause));
    } finally {
      setBusy(false);
    }
  }, []);

  return {
    status,
    busy,
    error,
    refresh,
    startLocal: () => run(() => startServer(false)),
    startLan: () => run(() => startServer(true)),
    stop: () => run(() => stopServer()),
  };
}
