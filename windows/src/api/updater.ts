import { getVersion } from "@tauri-apps/api/app";
import { relaunch } from "@tauri-apps/plugin-process";
import { check, type Update } from "@tauri-apps/plugin-updater";

export type UpdateCandidate = {
  update: Update;
  version: string;
  notes: string | null;
};

export type DownloadProgress =
  | { phase: "started"; total: number | null }
  | { phase: "progress"; chunk: number }
  | { phase: "installing" };

export function currentVersion(): Promise<string> {
  return getVersion();
}

/**
 * Queries the pinned update endpoint. Returns null when the installed build is
 * already the newest one; the manifest and its signature are verified by the
 * Tauri updater before anything is downloaded.
 */
export async function checkForUpdate(): Promise<UpdateCandidate | null> {
  const update = await check();
  if (!update) return null;
  return { update, version: update.version, notes: update.body ?? null };
}

/**
 * Downloads the signed installer and starts it. On Windows the application is
 * closed by the installer, so a successful call usually does not return.
 */
export async function installUpdate(
  candidate: UpdateCandidate,
  report: (progress: DownloadProgress) => void,
): Promise<void> {
  await candidate.update.downloadAndInstall((event) => {
    switch (event.event) {
      case "Started":
        report({ phase: "started", total: event.data.contentLength ?? null });
        break;
      case "Progress":
        report({ phase: "progress", chunk: event.data.chunkLength });
        break;
      case "Finished":
        report({ phase: "installing" });
        break;
    }
  });
}

export async function restartApp(): Promise<void> {
  await relaunch();
}
