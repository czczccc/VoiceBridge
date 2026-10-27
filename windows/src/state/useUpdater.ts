import { useCallback, useEffect, useRef, useState } from "react";

import {
  checkForUpdate,
  currentVersion,
  installUpdate,
  restartApp,
  type DownloadProgress,
  type UpdateCandidate,
} from "../api/updater";

export type UpdatePhase =
  | "idle"
  | "checking"
  | "available"
  | "uptodate"
  | "downloading"
  | "installing"
  | "error";

function describe(cause: unknown): string {
  if (typeof cause === "string") return cause;
  if (cause instanceof Error) return cause.message;
  return "更新失败，请稍后重试";
}

export function useUpdater() {
  const candidate = useRef<UpdateCandidate | null>(null);
  const [version, setVersion] = useState<string | null>(null);
  const [phase, setPhase] = useState<UpdatePhase>("idle");
  const [nextVersion, setNextVersion] = useState<string | null>(null);
  const [notes, setNotes] = useState<string | null>(null);
  const [downloaded, setDownloaded] = useState(0);
  const [total, setTotal] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    currentVersion()
      .then(setVersion)
      .catch(() => setVersion(null));
  }, []);

  const checkNow = useCallback(async () => {
    setPhase("checking");
    setError(null);
    try {
      const found = await checkForUpdate();
      candidate.current = found;
      setNextVersion(found?.version ?? null);
      setNotes(found?.notes ?? null);
      setPhase(found ? "available" : "uptodate");
    } catch (cause) {
      candidate.current = null;
      setError(describe(cause));
      setPhase("error");
    }
  }, []);

  const install = useCallback(async () => {
    const found = candidate.current;
    if (!found) return;
    setDownloaded(0);
    setTotal(null);
    setError(null);
    setPhase("downloading");
    const report = (progress: DownloadProgress) => {
      if (progress.phase === "started") {
        setTotal(progress.total);
      } else if (progress.phase === "progress") {
        setDownloaded((value) => value + progress.chunk);
      } else {
        setPhase("installing");
      }
    };
    try {
      await installUpdate(found, report);
      // Reaching this point means the installer did not close us; restart to
      // pick up the new build.
      await restartApp();
    } catch (cause) {
      setError(describe(cause));
      setPhase("error");
    }
  }, []);

  return {
    version,
    phase,
    nextVersion,
    notes,
    downloaded,
    total,
    error,
    checkNow,
    install,
  };
}
