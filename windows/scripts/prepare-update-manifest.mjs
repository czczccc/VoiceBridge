import fs from "node:fs";
import path from "node:path";

const runNumber = process.env.GITHUB_RUN_NUMBER;
const repository = process.env.GITHUB_REPOSITORY;
if (!runNumber || !repository) {
  throw new Error("GITHUB_RUN_NUMBER and GITHUB_REPOSITORY are required");
}

const bundle = "src-tauri/target/release/bundle/nsis";
const setup = fs
  .readdirSync(bundle)
  .find((name) => name.endsWith("-setup.exe"));
if (!setup) throw new Error("NSIS installer not found");

const signature = fs
  .readFileSync(path.join(bundle, `${setup}.sig`), "utf8")
  .trim();
if (!signature) throw new Error("Installer signature is empty");

const version = `0.1.1-test.${runNumber}`;
const asset = `VoiceBridge-${runNumber}-setup.exe`;
const output = "update-release";
fs.mkdirSync(output, { recursive: true });
fs.copyFileSync(path.join(bundle, setup), path.join(output, asset));
fs.writeFileSync(path.join(output, `${asset}.sig`), `${signature}\n`);

const manifest = {
  version,
  pub_date: new Date().toISOString().replace(/\.\d+Z$/, "Z"),
  notes: `VoiceBridge 测试版本 ${version}`,
  platforms: {
    "windows-x86_64": {
      signature,
      url: `https://github.com/${repository}/releases/download/windows-testing/${asset}`,
    },
  },
};
fs.writeFileSync(
  path.join(output, "latest.json"),
  `${JSON.stringify(manifest, null, 2)}\n`,
);
console.log(`prepared ${asset} for ${version}`);
