// protocol/fixtures/, the golden messages that the app's tests check as well:
// `<type>.json` holds a request and its response, `events.json` the events and
// `errors.json` the error responses. protocol/compatibility/ freezes them per
// protocol version.
import { existsSync, readdirSync, readFileSync } from "node:fs";
import type { JsonObject } from "../../src/lib/protocol/framing";
import { PROTOCOL_VERSIONS } from "../../src/lib/protocol/messages";

export interface Exchange {
  request: JsonObject;
  response: JsonObject;
}

/** The messages of a protocol version, as `compatibility/v<version>.json` freezes them. */
export interface Corpus {
  exchanges: Record<string, Exchange>;
  events: JsonObject[];
  errors: JsonObject[];
}

const directory = new URL("../../protocol/fixtures/", import.meta.url);
const read = (file: string): unknown => JSON.parse(readFileSync(new URL(file, directory), "utf8"));

export const EVENTS_FILE = "events.json";
export const ERRORS_FILE = "errors.json";

/** The names of the fixture files. */
export const fixtureFiles: string[] = readdirSync(directory).sort();

/** The request and response of each request type. */
export const exchanges: Record<string, Exchange> = Object.fromEntries(
  fixtureFiles
    .filter((file) => file !== EVENTS_FILE && file !== ERRORS_FILE)
    .map((file) => [file.replace(/\.json$/, ""), read(file) as Exchange]),
);

export const events = read(EVENTS_FILE) as JsonObject[];
export const errors = read(ERRORS_FILE) as JsonObject[];

/** The protocol versions the extension speaks, PROTOCOL_VERSIONS.min to max. */
export const supportedVersions: number[] = Array.from(
  { length: PROTOCOL_VERSIONS.max - PROTOCOL_VERSIONS.min + 1 },
  (_, i) => PROTOCOL_VERSIONS.min + i,
);

/** The frozen messages of protocol [version]. */
export function frozen(version: number): Corpus {
  const file = `../compatibility/v${version}.json`;
  if (!existsSync(new URL(file, directory))) {
    throw new Error(`No compatibility snapshot of protocol v${version}: add protocol/compatibility/v${version}.json`);
  }
  return read(file) as Corpus;
}

/** A copy of the fields of the request of [type], as the extension passes them: without `id` and `type`. */
export function requestFields(type: string): JsonObject {
  const { id: _, type: _type, ...fields } = exchange(type).request;
  return structuredClone(fields);
}

/** A copy of the fields of the response of [type], as the app answers: without `id` and `ok`. */
export function responseFields(type: string): JsonObject {
  const { id: _, ok: _ok, ...fields } = exchange(type).response;
  return structuredClone(fields);
}

function exchange(type: string): Exchange {
  const exchange = exchanges[type];
  if (exchange === undefined) throw new Error(`No fixture for ${type}`);
  return exchange;
}
