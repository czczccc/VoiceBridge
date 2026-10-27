import { useCallback, useEffect, useState } from "react";

import {
  describeSessionState,
  getAutoSync,
  getInputSession,
  setAutoSync,
  stopInputSession,
  type InputSession,
} from "../api/session";

const POLL_MS = 1500;

function describe(cause: unknown): string {
  if (typeof cause === "string") return cause;
  if (cause instanceof Error) return cause.message;
  return "操作失败，请重试";
}

/**
 * Input sessions are armed by the phone, not by a button here: the user picks the
 * target with the mouse, then starts typing on the phone. This panel only reports
 * the state and offers an emergency stop plus a switch for the whole feature.
 */
export function SessionPanel() {
  const [session, setSession] = useState<InputSession | null>(null);
  const [autoEnabled, setAutoEnabled] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const [next, enabled] = await Promise.all([
        getInputSession(),
        getAutoSync(),
      ]);
      setSession(next);
      setAutoEnabled(enabled);
    } catch {
      // The backend may still be starting; the next poll retries.
    }
  }, []);

  useEffect(() => {
    void refresh();
    const timer = window.setInterval(() => void refresh(), POLL_MS);
    return () => window.clearInterval(timer);
  }, [refresh]);

  const toggleAuto = async () => {
    setBusy(true);
    setError(null);
    try {
      const enabled = await setAutoSync(!(autoEnabled ?? true));
      setAutoEnabled(enabled);
      if (!enabled) setSession(null);
    } catch (cause) {
      setError(describe(cause));
    } finally {
      setBusy(false);
    }
  };

  const stop = async () => {
    setBusy(true);
    setError(null);
    try {
      await stopInputSession();
      setSession(null);
    } catch (cause) {
      setError(describe(cause));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-label="输入会话">
      <h2>输入会话</h2>
      <p className="hint">
        在电脑上点好要输入的地方，然后在手机上开始打字或语音输入，文字会自动写到那个窗口。
        电脑端不需要点「开始」。焦点一旦切走，本次会话立即暂停，等你回到目标窗口重新输入时再自动开始。
      </p>
      <dl className="facts">
        <div>
          <dt>自动同步</dt>
          <dd>
            {autoEnabled === null
              ? "读取中…"
              : autoEnabled
                ? "已开启"
                : "已关闭"}
          </dd>
        </div>
        <div>
          <dt>状态</dt>
          <dd>
            {session ? describeSessionState(session.state) : "等待手机输入"}
          </dd>
        </div>
        <div>
          <dt>目标窗口</dt>
          <dd>{session?.targetLabel ?? "—"}</dd>
        </div>
      </dl>
      {error ? (
        <p className="error" role="alert">
          {error}
        </p>
      ) : null}
      <div className="actions">
        <button
          type="button"
          onClick={toggleAuto}
          disabled={busy || autoEnabled === null}
        >
          {autoEnabled ? "关闭自动同步" : "开启自动同步"}
        </button>
        <button
          type="button"
          onClick={stop}
          disabled={busy || session === null}
        >
          停止当前会话
        </button>
      </div>
    </section>
  );
}
