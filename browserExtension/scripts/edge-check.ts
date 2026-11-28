// Prints `ok` if Microsoft Edge Add-ons accepts the API key, without
// uploading or submitting anything (edgeCheckKey). Reads the API key from
// EDGE_CLIENT_ID and EDGE_API_KEY.
// `node scripts/edge-check.ts <product ID>`
import { fail } from "./cli.ts";
import { EDGE_HINTS, EdgeError, edgeCheckKey } from "./edge.ts";

const [productId] = process.argv.slice(2);
const clientId = process.env.EDGE_CLIENT_ID;
const apiKey = process.env.EDGE_API_KEY;
if (!productId) {
  console.error("Usage: node scripts/edge-check.ts <product ID>");
  process.exit(2);
}
if (!clientId || !apiKey) {
  console.error("Set EDGE_CLIENT_ID and EDGE_API_KEY to an API key of Microsoft Edge Add-ons.");
  process.exit(2);
}
try {
  await edgeCheckKey(productId, { clientId, apiKey });
  console.log("ok");
} catch (error) {
  // The 404 hint never shows here: a 404 is the check's success.
  fail(error, error instanceof EdgeError ? EDGE_HINTS[error.status] : undefined);
}
