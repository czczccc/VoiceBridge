import { PairingPanel } from "./components/PairingPanel";
import { ServerPanel } from "./components/ServerPanel";
import { SessionPanel } from "./components/SessionPanel";
import { UpdatePanel } from "./components/UpdatePanel";

export default function App() {
  return (
    <main>
      <p className="eyebrow">Android ↔ Windows</p>
      <h1>VoiceBridge</h1>
      <p>手机输入，电脑接续。</p>
      <ServerPanel />
      <PairingPanel />
      <SessionPanel />
      <UpdatePanel />
      <section aria-label="后台运行">
        <h2>后台运行</h2>
        <p>
          点窗口右上角的 X
          只会收起窗口，连接、配对与输入同步继续在后台运行。要真正退出，请右键任务栏托盘里的
          VoiceBridge 图标，选择「退出 VoiceBridge」。
        </p>
      </section>
      <section aria-label="功能状态">
        <h2>后续版本</h2>
        <p>双向纯文本剪切板与更多设置将在后续版本提供。</p>
      </section>
    </main>
  );
}
