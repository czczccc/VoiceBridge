import { ServerPanel } from "./components/ServerPanel";
import { UpdatePanel } from "./components/UpdatePanel";

export default function App() {
  return (
    <main>
      <p className="eyebrow">Android ↔ Windows</p>
      <h1>VoiceBridge</h1>
      <p>手机输入，电脑接续。</p>
      <ServerPanel />
      <UpdatePanel />
      <section aria-label="功能状态">
        <h2>后续版本</h2>
        <p>设备配对、语音文本同步与剪切板功能将在后续版本提供。</p>
      </section>
    </main>
  );
}
