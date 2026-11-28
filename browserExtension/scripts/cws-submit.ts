// Uploads a zip, or a CRX signed for the verified CRX uploads
// (scripts/crx.ts), as the new version of the extension on the Chrome Web
// Store and submits it for review; the version is published once approved.
// Prints the submitted revision's state, `PENDING_REVIEW` for one, and the
// store's warnings to stderr, as annotations on GitHub Actions. Reads the
// service account from CWS_SERVICE_ACCOUNT_EMAIL and
// CWS_SERVICE_ACCOUNT_PRIVATE_KEY.
// `node scripts/cws-submit.ts <publisher ID> <extension ID> <package.zip|package.crx>`
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fail } from "./cli.ts";
import { CWS_AUTH_HINT, CWS_HINTS, CwsError, cwsAccessToken, cwsSubmit, cwsWarningLine } from "./cws.ts";

const [publisherId, extensionId, file] = process.argv.slice(2);
const email = process.env.CWS_SERVICE_ACCOUNT_EMAIL;
const privateKey = process.env.CWS_SERVICE_ACCOUNT_PRIVATE_KEY;
if (!publisherId || !extensionId || !file || !/\.(zip|crx)$/i.test(file)) {
  console.error("Usage: node scripts/cws-submit.ts <publisher ID> <extension ID> <package.zip|package.crx>");
  process.exit(2);
}
if (!email || !privateKey) {
  console.error(
    "Set CWS_SERVICE_ACCOUNT_EMAIL and CWS_SERVICE_ACCOUNT_PRIVATE_KEY to a service account of the Chrome Web Store API.",
  );
  process.exit(2);
}
try {
  const pkg = { fileName: path.basename(file), bytes: await readFile(file) };
  const token = await cwsAccessToken({ email, privateKey });
  const { state, warnings } = await cwsSubmit({ publisherId, extensionId }, token, pkg);
  // Stdout is the state only: the workflow reads it.
  const githubActions = process.env.GITHUB_ACTIONS === "true";
  for (const warning of warnings) console.error(cwsWarningLine(warning, githubActions));
  console.log(state);
} catch (error) {
  fail(error, error instanceof CwsError ? (error.auth ? CWS_AUTH_HINT : CWS_HINTS[error.status]) : undefined);
}
