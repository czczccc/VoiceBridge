import { useUpdater } from "../state/useUpdater";

function formatBytes(value: number): string {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / (1024 * 1024)).toFixed(1)} MB`;
}

export function UpdatePanel() {
  const {
    version,
    phase,
    nextVersion,
    notes,
    downloaded,
    total,
    error,
    checkNow,
    install,
  } = useUpdater();

  const percent =
    total && total > 0
      ? Math.min(100, Math.round((downloaded / total) * 100))
      : null;
  const busy =
    phase === "checking" || phase === "downloading" || phase === "installing";

  return (
    <section aria-label="版本更新">
      <h2>版本更新</h2>
      <dl className="facts">
        <div>
          <dt>当前版本</dt>
          <dd>{version ?? "未知"}</dd>
        </div>
        <div>
          <dt>最新版本</dt>
          <dd>
            {phase === "checking"
              ? "检查中…"
              : phase === "uptodate"
                ? "已是最新"
                : phase === "error"
                  ? "检查失败"
                  : (nextVersion ?? "未检查")}
          </dd>
        </div>
      </dl>
      {phase === "downloading" || phase === "installing" ? (
        <div
          className="progress"
          role="progressbar"
          aria-valuenow={percent ?? undefined}
        >
          <div className="progress-bar" style={{ width: `${percent ?? 5}%` }} />
          <p className="hint">
            {phase === "installing"
              ? "正在安装，应用会自动关闭并重新打开…"
              : total
                ? `已下载 ${formatBytes(downloaded)} / ${formatBytes(total)}（${percent}%）`
                : `已下载 ${formatBytes(downloaded)}`}
          </p>
        </div>
      ) : null}
      {phase === "available" && notes ? (
        <p className="hint">更新说明：{notes}</p>
      ) : null}
      {phase === "available" ? (
        <p className="hint">
          下载完成后安装程序会自动替换当前版本，不需要手动下载安装包。
        </p>
      ) : null}
      {error ? (
        <p className="error" role="alert">
          {error}
        </p>
      ) : null}
      <div className="actions">
        <button type="button" onClick={checkNow} disabled={busy}>
          检查更新
        </button>
        <button
          type="button"
          onClick={install}
          disabled={busy || phase !== "available"}
        >
          下载并安装
        </button>
      </div>
    </section>
  );
}
