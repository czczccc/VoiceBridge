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
      <section aria-label="功能状态">
        <h2>后续版本</h2>
        <p>双向纯文本剪切板、托盘与设置将在后续版本提供。</p>
      </section>
    </main>
  );
}
