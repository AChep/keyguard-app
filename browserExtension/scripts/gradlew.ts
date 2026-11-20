// Runs the repository's Gradle wrapper from the repository root with this
// script's arguments, and exits with its exit code: `gradlew.bat` on Windows,
// where cmd can't run `./gradlew`, and `./gradlew` elsewhere.
import { spawnSync } from "node:child_process";
import path from "node:path";

const repository = path.join(import.meta.dirname, "../..");
const args = process.argv.slice(2);
const result =
  process.platform === "win32"
    ? // Node starts a .bat only through the shell. The arguments are task names.
      spawnSync(["gradlew.bat", ...args].join(" "), { cwd: repository, stdio: "inherit", shell: true })
    : spawnSync("./gradlew", args, { cwd: repository, stdio: "inherit" });
if (result.error !== undefined) throw result.error;
process.exit(result.status ?? 1);
