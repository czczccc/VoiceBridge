import { useServer } from "../state/useServer";

/** `SHA256:abcd…` becomes `SHA256:ab:cd:…` so it is easy to compare with the phone. */
function formatFingerprint(value: string): string {
  const [prefix, hex] = value.split(":");
  if (!hex) return value;
  return `${prefix}:${hex.match(/.{1,2}/g)?.join(":") ?? hex}`;
}

export function ServerPanel() {
  const { status, busy, error, refresh, startLocal, startLan, stop } =
    useServer();
  const running = status?.running ?? false;
  const endpoint =
    status?.address && status.port ? `${status.address}:${status.port}` : "—";

  return (
    <section aria-label="本地服务">
      <h2>本地服务</h2>
      <p className="state" data-running={running}>
        <span className="dot" aria-hidden="true" />
        {running ? "运行中" : status ? "已停止" : "读取状态…"}
      </p>
      <dl className="facts">
        <div>
          <dt>监听地址</dt>
          <dd>{running ? endpoint : "未监听"}</dd>
        </div>
        <div>
          <dt>局域网访问</dt>
          <dd>未开放（需先完成设备配对与加密认证）</dd>
        </div>
        <div>
          <dt>证书指纹</dt>
          <dd>
            {status?.fingerprint ? (
              <code>{formatFingerprint(status.fingerprint)}</code>
            ) : (
              "生成中…"
            )}
          </dd>
        </div>
      </dl>
      {!running && status ? (
        <p className="hint">
          当前只监听本机回环地址，仅用于本机调试。未认证的明文端口不会对局域网开放。
        </p>
      ) : null}
      {error ? (
        <p className="error" role="alert">
          {error}
        </p>
      ) : null}
      <div className="actions">
        <button type="button" onClick={startLocal} disabled={busy || running}>
          启动本机服务
        </button>
        <button type="button" onClick={stop} disabled={busy || !running}>
          停止
        </button>
        <button type="button" onClick={refresh} disabled={busy}>
          刷新状态
        </button>
        <button
          type="button"
          onClick={startLan}
          disabled={busy || running}
          title="设备配对与加密认证完成后开放"
        >
          允许局域网访问
        </button>
      </div>
    </section>
  );
}
