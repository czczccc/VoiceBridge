import { invoke } from "@tauri-apps/api/core";

export interface InputSession {
  sessionId: string;
  targetLabel: string;
  state: string;
}

export function getInputSession(): Promise<InputSession | null> {
  return invoke<InputSession | null>("input_session_status");
}

/** Whether the phone may arm a session without a local click. */
export function getAutoSync(): Promise<boolean> {
  return invoke<boolean>("auto_sync_status");
}

export function setAutoSync(enabled: boolean): Promise<boolean> {
  return invoke<boolean>("set_auto_sync", { enabled });
}

export function stopInputSession(): Promise<string | null> {
  return invoke<string | null>("stop_input_session");
}

export function describeSessionState(state: string): string {
  switch (state) {
    case "armed":
      return "已就绪，等待手机开始输入";
    case "active":
      return "正在同步输入";
    case "paused-focus":
      return "焦点已改变，已暂停（需重新开始会话）";
    case "stopping":
      return "正在停止";
    case "error":
      return "注入失败，已停止同步";
    default:
      return state;
  }
}
