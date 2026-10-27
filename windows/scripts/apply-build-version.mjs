import fs from "node:fs";

const runNumber = process.env.GITHUB_RUN_NUMBER;
if (!runNumber) throw new Error("GITHUB_RUN_NUMBER is required");
const publish = process.env.PUBLISH === "true";
const version = `0.1.1-test.${runNumber}`;

const configPath = "src-tauri/tauri.conf.json";
const config = JSON.parse(fs.readFileSync(configPath, "utf8"));
const pubkey = config.plugins?.updater?.pubkey ?? "";
if (publish && !pubkey) {
  throw new Error(
    "Updater public key missing. Run scripts/setup-windows-updater.ps1 once.",
  );
}
config.version = version;
config.bundle.createUpdaterArtifacts = publish;
if (!publish) config.plugins.updater.pubkey = "";
fs.writeFileSync(configPath, `${JSON.stringify(config, null, 2)}\n`);

console.log(`build version ${version}, updater artifacts ${publish}`);
