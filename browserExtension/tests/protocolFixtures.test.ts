// protocol/fixtures/, which the app's tests check as well, against the
// extension: the TypeScript compiler checks that each fixture `satisfies` its
// message type, excess properties included, and the runtime checks read each
// response, credential and event.
import { createHash } from "node:crypto";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import ts from "typescript";
import { describe, expect, it } from "vitest";
import type { JsonObject } from "../src/lib/protocol/framing";
import { parseEvent, parseResponse, type RequestType } from "../src/lib/protocol/messages";
import { credentialProblem } from "../src/lib/webauthn/credential";
import { credentialFromJSON } from "../src/lib/webauthn/json";
import { errors, events, exchanges, frozenV1, requestFields, responseFields } from "./support/fixtures";

const ROOT = fileURLToPath(new URL("..", import.meta.url));
/** Not a [RequestType]: the session sends it itself. */
const CANCEL = "cancel";

interface Check {
  /** The fixture it checks, for the report. */
  what: string;
  code: string;
}

/** A statement per fixture that it `satisfies` its type, and that every type has a fixture. */
function checks(): Check[] {
  const literal = (value: unknown) => `(${JSON.stringify(value)})`;
  const all = (keys: string[]) => literal(Object.fromEntries(keys.map((key) => [key, true])));
  const result: Check[] = [];
  for (const type of Object.keys(exchanges)) {
    const name = JSON.stringify(type);
    const [request, response] =
      type === CANCEL ? ["CancelRequest", "Record<string, never>"] : [`RequestOf<${name}>`, `ResponseOf<${name}>`];
    result.push({ what: `${type}.json request`, code: `${literal(requestFields(type))} satisfies ${request};` });
    result.push({ what: `${type}.json response`, code: `${literal(responseFields(type))} satisfies ${response};` });
  }
  for (const event of events) {
    result.push({ what: `events.json ${String(event.event)}`, code: `${literal(event)} satisfies ProtocolEvent;` });
  }
  for (const { id: _, ok: _ok, ...fields } of errors) {
    result.push({ what: `errors.json ${String(fields.error)}`, code: `${literal(fields)} satisfies { error: ProtocolErrorCode };` });
  }
  const types = Object.keys(exchanges).filter((type) => type !== CANCEL);
  result.push({ what: "request types", code: `${all(types)} satisfies Record<RequestType, true>;` });
  result.push({ what: "events", code: `${all(events.map((it) => String(it.event)))} satisfies Record<ProtocolEvent["event"], true>;` });
  result.push({ what: "error codes", code: `${all(errors.map((it) => String(it.error)))} satisfies Record<ProtocolErrorCode, true>;` });
  return result;
}

/** Type-checks [checks] as a module beside tsconfig.json, with its options; one line per error. */
function typeErrors(checks: Check[]): string[] {
  const file = resolve(ROOT, "protocol-fixtures.check.ts");
  const header = `import type { CancelRequest, ProtocolErrorCode, ProtocolEvent, RequestOf, RequestType, ResponseOf } from "./src/lib/protocol/messages";`;
  const source = [header, ...checks.map((it) => it.code)].join("\n");
  const config = ts.getParsedCommandLineOfConfigFile(
    resolve(ROOT, "tsconfig.json"),
    {},
    {
      ...ts.sys,
      onUnRecoverableConfigFileDiagnostic: (diagnostic) => {
        throw new Error(ts.flattenDiagnosticMessageText(diagnostic.messageText, "\n"));
      },
    },
  );
  const options = { ...config!.options, noEmit: true };
  const host = ts.createCompilerHost(options, true);
  const { getSourceFile, fileExists, readFile } = host;
  const isCheck = (name: string) => resolve(name) === file;
  host.getSourceFile = (name, language, ...rest) =>
    isCheck(name) ? ts.createSourceFile(name, source, language, true) : getSourceFile.call(host, name, language, ...rest);
  host.fileExists = (name) => isCheck(name) || fileExists.call(host, name);
  host.readFile = (name) => (isCheck(name) ? source : readFile.call(host, name));
  const program = ts.createProgram([file], options, host);
  const sourceFile = program.getSourceFile(file)!;
  return [...program.getSyntacticDiagnostics(sourceFile), ...program.getSemanticDiagnostics(sourceFile)].map((diagnostic) => {
    const line = sourceFile.getLineAndCharacterOfPosition(diagnostic.start ?? 0).line;
    const what = line === 0 ? "imports" : checks[line - 1]!.what;
    return `${what}: ${ts.flattenDiagnosticMessageText(diagnostic.messageText, " ")}`;
  });
}

describe("protocol fixtures", () => {
  it("keeps the v1 compatibility snapshot fixed independently of the current fixtures", () => {
    const digest = createHash("sha256").update(JSON.stringify(frozenV1)).digest("hex");
    expect(digest).toBe("f827486734aafe619e7957fe3509e781ab9c1e4069b24abea5ad12351e1a46a8");
  });

  it("satisfy the message types, and cover each of them", () => {
    expect(typeErrors(checks())).toEqual([]);
  });

  it("have the envelopes of §4", () => {
    for (const [type, exchange] of Object.entries(exchanges)) {
      expect(Object.keys(exchange).sort(), type).toEqual(["request", "response"]);
      const { request, response } = exchange;
      expect(request.type, type).toBe(type);
      expect(Number.isSafeInteger(request.id) && (request.id as number) >= 1, type).toBe(true);
      expect(response.id, type).toBe(request.id);
      expect(response.ok, type).toBe(true);
    }
    for (const error of errors) {
      expect(Object.keys(error).sort()).toEqual(["error", "id", "ok"]);
      expect(Number.isSafeInteger(error.id) && (error.id as number) >= 1).toBe(true);
      expect(error.ok).toBe(false);
    }
  });

  it("pass the checks of responses and events", () => {
    for (const [type, { response }] of Object.entries(exchanges)) {
      if (type !== CANCEL) expect(() => parseResponse(type as RequestType, response), type).not.toThrow();
    }
    expect(events.map(parseEvent)).toEqual(events);
  });

  it("answer passkey requests with credentials for their origin and challenge", () => {
    for (const [type, kind] of [
      ["passkey_get", "get"],
      ["passkey_create", "create"],
    ] as const) {
      const { origin, options } = requestFields(type) as { origin: string; options: JsonObject };
      const credential = responseFields(type).credential as JsonObject;
      expect(credentialProblem(credential, { kind, origin, challenge: options.challenge }), type).toBeNull();
      // The page sees a platform authenticator, whatever the app says.
      expect(credentialFromJSON(credential, kind, {}).toJSON(), type).toEqual({ ...credential, authenticatorAttachment: "platform" });
    }
  });
});
