// Prints `exists` if the Microsoft Edge Add-ons website shows the version of
// the extension or a later one, `absent` if it shows an earlier one or none,
// and `unknown` if it can't tell. The release workflow submits unless the
// version exists. The website's endpoint is undocumented, so `unknown` exits
// 0 with the reason on stderr, and the release submits anyway.
// `node scripts/edge-version.ts <CRX ID> <version>`
import { errorText } from "./cli.ts";
import { edgePublicVersion } from "./edge.ts";
import { compareVersions, isVersion } from "./version.ts";

const [crxId, version] = process.argv.slice(2);
if (!crxId || !version || !isVersion(version)) {
  console.error("Usage: node scripts/edge-version.ts <CRX ID> <version>");
  process.exit(2);
}
try {
  const shown = await edgePublicVersion(crxId);
  console.log(shown !== null && compareVersions(shown, version) >= 0 ? "exists" : "absent");
} catch (error) {
  console.error(`Can't tell the public version: ${errorText(error)}`);
  console.log("unknown");
}
