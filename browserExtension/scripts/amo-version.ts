// Prints `exists` if addons.mozilla.org has the version of the add-on, in any
// channel and state, or `absent` if not. The release workflow submits only an
// absent version. Reads the API key from AMO_JWT_ISSUER and AMO_JWT_SECRET.
// `node scripts/amo-version.ts <add-on ID> <version>`
import { AmoError, amoHasVersion } from "./amo.ts";

const HINTS: Record<number, string> = {
  401: "Check AMO_JWT_ISSUER, AMO_JWT_SECRET and the clock.",
  403: "The API key's account must be an author of the add-on.",
  404: "Upload the add-on's first version by hand.",
};

const [guid, version] = process.argv.slice(2);
const issuer = process.env.AMO_JWT_ISSUER;
const secret = process.env.AMO_JWT_SECRET;
if (!guid || !version) {
  console.error("Usage: node scripts/amo-version.ts <add-on ID> <version>");
  process.exit(2);
}
if (!issuer || !secret) {
  console.error("Set AMO_JWT_ISSUER and AMO_JWT_SECRET to an API key of addons.mozilla.org.");
  process.exit(2);
}
try {
  console.log((await amoHasVersion(guid, version, { issuer, secret })) ? "exists" : "absent");
} catch (error) {
  const hint = error instanceof AmoError ? HINTS[error.status] : undefined;
  // A failed fetch() names the network error in its cause.
  const cause = error instanceof Error && error.cause instanceof Error ? `: ${error.cause.message}` : "";
  console.error([`${error instanceof Error ? error.message : String(error)}${cause}`, hint].filter(Boolean).join("\n"));
  process.exit(1);
}
