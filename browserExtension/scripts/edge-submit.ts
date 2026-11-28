// Uploads the Chromium zip to Microsoft Edge Add-ons and submits it for
// certification, with the notes file for the testers. Prints `submitted`, or
// `in-review` if a submission, maybe of the same version, is still in review:
// the zip then stays the draft, not submitted. Reads the API key from
// EDGE_CLIENT_ID and EDGE_API_KEY.
// `node scripts/edge-submit.ts <product ID> <zip> <notes file>`
import { readFile } from "node:fs/promises";
import { fail } from "./cli.ts";
import { EDGE_HINTS, EdgeError, edgeSubmit } from "./edge.ts";

// For a failed operation, by its errorCode.
const CODE_HINTS: Record<string, string> = {
  CreateNotAllowed: "Submit the extension's first version in Partner Center by hand.",
  NoModulesUpdated:
    "The package is the same as the last submission, so there's nothing new to submit. This happens when the website's version check couldn't see a version that Edge Add-ons already has.",
};

const [productId, zipPath, notesPath] = process.argv.slice(2);
const clientId = process.env.EDGE_CLIENT_ID;
const apiKey = process.env.EDGE_API_KEY;
if (!productId || !zipPath || !notesPath) {
  console.error("Usage: node scripts/edge-submit.ts <product ID> <zip> <notes file>");
  process.exit(2);
}
if (!clientId || !apiKey) {
  console.error("Set EDGE_CLIENT_ID and EDGE_API_KEY to an API key of Microsoft Edge Add-ons.");
  process.exit(2);
}
try {
  const [zip, notes] = await Promise.all([readFile(zipPath), readFile(notesPath, "utf8")]);
  console.log(await edgeSubmit(productId, zip, notes.trim(), { clientId, apiKey }));
} catch (error) {
  const hint =
    error instanceof EdgeError
      ? error.operation
        ? CODE_HINTS[error.operation.errorCode ?? ""]
        : EDGE_HINTS[error.status]
      : undefined;
  fail(error, hint);
}
