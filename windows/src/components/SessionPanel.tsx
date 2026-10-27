import { useCallback, useEffect, useState } from "react";

import {
  describeSessionState,
  getInputSession,
  startInputSession,
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
 * Starts and stops the single input session. Starting waits three seconds so the
 * user can move focus from this window to the field they actually want filled.
 */
export function SessionPanel() {
  const [session, setSession] = useState<InputSession | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setSession(await getInputSession());
    } catch {
      // The backend may still be starting; the next poll retries.
    }
  }, []);

  useEffect(() => {
    void refresh();
    const timer = window.setInterval(() => void refresh(), POLL_MS);
    return () => window.clearInterval(timer);
  }, [refresh]);

  const start = async () => {
    setBusy(true);
    setError(null);
    try {
      setSession(await startInputSession());
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
        点击「开始输入会话」后有 3
        秒时间切换到要输入的位置；之后手机上的文字会写进那个窗口。
        焦点一旦改变，本次会话立即暂停，需要重新开始。
      </p>
      <dl className="facts">
        <div>
          <dt>状态</dt>
          <dd>{session ? describeSessionState(session.state) : "未开始"}</dd>
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
          onClick={start}
          disabled={busy || session !== null}
        >
          开始输入会话
        </button>
        <button
          type="button"
          onClick={stop}
          disabled={busy || session === null}
        >
          停止输入会话
        </button>
      </div>
    </section>
  );
}
