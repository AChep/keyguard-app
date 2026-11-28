// Prints what the Chrome Web Store has of a version of the extension: `exists`
// if the published version, or a submitted one that the store holds, is the
// same or newer; `pending` if an older version awaits review or publishing;
// `absent` if not. The release workflow submits only an absent version. Reads
// the service account from CWS_SERVICE_ACCOUNT_EMAIL and
// CWS_SERVICE_ACCOUNT_PRIVATE_KEY.
// `node scripts/cws-version.ts <publisher ID> <extension ID> <version>`
import { fail } from "./cli.ts";
import { CWS_AUTH_HINT, CWS_HINTS, CwsError, cwsAccessToken, cwsFetchStatus, cwsVersionStatus } from "./cws.ts";
import { isVersion } from "./version.ts";

const [publisherId, extensionId, version] = process.argv.slice(2);
const email = process.env.CWS_SERVICE_ACCOUNT_EMAIL;
const privateKey = process.env.CWS_SERVICE_ACCOUNT_PRIVATE_KEY;
if (!publisherId || !extensionId || !version || !isVersion(version)) {
  console.error("Usage: node scripts/cws-version.ts <publisher ID> <extension ID> <version>");
  process.exit(2);
}
if (!email || !privateKey) {
  console.error(
    "Set CWS_SERVICE_ACCOUNT_EMAIL and CWS_SERVICE_ACCOUNT_PRIVATE_KEY to a service account of the Chrome Web Store API.",
  );
  process.exit(2);
}
try {
  const token = await cwsAccessToken({ email, privateKey });
  console.log(cwsVersionStatus(await cwsFetchStatus({ publisherId, extensionId }, token), version));
} catch (error) {
  fail(error, error instanceof CwsError ? (error.auth ? CWS_AUTH_HINT : CWS_HINTS[error.status]) : undefined);
}
