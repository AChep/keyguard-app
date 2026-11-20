import { beforeAll, describe, expect, it } from "vitest";
import { NativeCryptoError } from "../src/lib/crypto/nativeCrypto";
import type { NativeCryptoNoise } from "../src/lib/crypto/noise";
import { loadNoise } from "../src/lib/crypto/wasm";
import { MAX_DEPTH, MessageTooDeep, nestsAtMost, type JsonObject } from "../src/lib/protocol/framing";
import { InvalidResponse, type ProtocolEvent, type RequestType } from "../src/lib/protocol/messages";
import {
  MAX_IN_FLIGHT,
  ProtocolError,
  ProtocolSession,
  SessionClosed,
  SUITE_ID,
  type SessionEnd,
} from "../src/lib/protocol/session";
import { FakeApp, type FakeAppOptions } from "./support/fakeApp";
import { errors, events, exchanges, frozenV1 } from "./support/fixtures";

const ORIGIN = "chrome-extension://abcdefghijklmnopabcdefghijklmnop/";
let noise: NativeCryptoNoise;

beforeAll(async () => {
  noise = await loadNoise();
});

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

/** A session wired to a fake app, frames delivered asynchronously both ways. */
function connect(
  options: Partial<FakeAppOptions> = {},
  session: { pinnedAppKey?: Uint8Array | null; handshakeTimeoutMs?: number } = {},
) {
  const extension = noise.generateKeyPair();
  const sent: Uint8Array[] = [];
  const events: ProtocolEvent[] = [];
  const ends: SessionEnd[] = [];
  // Both sides deliver frames asynchronously; `start()` runs once both exist.
  const protocol: ProtocolSession = new ProtocolSession({
    noise,
    staticPrivateKey: extension.privateKey,
    origin: ORIGIN,
    pinnedAppKey: session.pinnedAppKey ?? null,
    send: (frame) => {
      sent.push(frame);
      queueMicrotask(() => app.receive(frame));
    },
    onEvent: (event) => events.push(event),
    onEnd: (end) => ends.push(end),
    ...(session.handshakeTimeoutMs !== undefined ? { handshakeTimeoutMs: session.handshakeTimeoutMs } : {}),
  });
  const app = new FakeApp({ noise, origin: ORIGIN, ...options }, (frame) =>
    queueMicrotask(() => protocol.receive(frame)),
  );
  protocol.start();
  return { extension, app, protocol, sent, events, ends };
}

const hello = { protocol: 1, capabilities: [] };

describe("ProtocolSession", () => {
  it("sends the suite ID, runs XX and exchanges hello", async () => {
    const { extension, app, protocol, sent } = connect();
    const handshake = await protocol.established;
    expect(sent[0]).toEqual(Uint8Array.of(SUITE_ID));
    expect(sent.slice(1, 3).map((it) => it.length)).toEqual([32, 64]);
    expect(handshake.remoteStaticKey).toEqual(app.keys.publicKey);
    await flush();
    expect(app.extensionKey).toEqual(extension.publicKey);
    expect(handshake.handshakeHash).toEqual(app.handshakeHash);

    const response = await protocol.request("hello", hello);
    expect(response).toMatchObject({ protocol: 1, paired: false, vault: "locked" });
    expect(app.requests[0]).toEqual({ id: 1, type: "hello", ...hello });
  });

  it("checks the pinned key before message 3", async () => {
    const pinned = noise.generateKeyPair().publicKey;
    const { app, protocol, sent, ends } = connect({}, { pinnedAppKey: pinned });
    await expect(protocol.established).rejects.toBeInstanceOf(SessionClosed);
    await flush();
    expect(ends).toEqual([{ reason: "pinned_key_mismatch" }]);
    // Only the suite ID and message 1: the app never learns our key.
    expect(sent).toHaveLength(2);
    expect(app.extensionKey).toBeNull();
    await expect(protocol.request("unlock", {})).rejects.toBeInstanceOf(SessionClosed);
  });

  it("accepts the pinned key", async () => {
    const keys = noise.generateKeyPair();
    const { protocol } = connect({ keys }, { pinnedAppKey: keys.publicKey });
    await expect(protocol.established).resolves.toMatchObject({ remoteStaticKey: keys.publicKey });
  });

  it("ends the session on a handshake payload", async () => {
    const { protocol, ends } = connect({ message2Payload: Uint8Array.of(1) });
    await expect(protocol.established).rejects.toBeInstanceOf(SessionClosed);
    expect(ends[0]?.reason).toBe("violation");
  });

  it("ends a handshake that doesn't finish in time", async () => {
    const { protocol, ends } = connect({ silent: true }, { handshakeTimeoutMs: 5 });
    await expect(protocol.established).rejects.toBeInstanceOf(SessionClosed);
    expect(ends).toEqual([{ reason: "handshake_timeout" }]);
  });

  it("ends the session when the handshake fails to start or to send a frame", async () => {
    const failure = new NativeCryptoError("noise_handshake_open", "RESOURCE_LIMIT");
    const broken = Object.assign(Object.create(noise) as NativeCryptoNoise, {
      xx: () => {
        throw failure;
      },
    });
    const disconnected = new Error("Attempting to use a disconnected port object");
    // The crypto can't open the handshake, or sending the suite ID, message 1 or message 3 throws.
    const cases: [string, NativeCryptoNoise, number | null, Error][] = [
      ["open", broken, null, failure],
      ["suite ID", noise, 0, disconnected],
      ["message 1", noise, 1, disconnected],
      ["message 3", noise, 2, disconnected],
    ];
    for (const [name, sessionNoise, failingFrame, error] of cases) {
      const ends: SessionEnd[] = [];
      let frames = 0;
      const protocol: ProtocolSession = new ProtocolSession({
        noise: sessionNoise,
        staticPrivateKey: noise.generateKeyPair().privateKey,
        origin: ORIGIN,
        pinnedAppKey: null,
        send: (frame) => {
          if (frames++ === failingFrame) throw disconnected;
          queueMicrotask(() => app.receive(frame));
        },
        onEnd: (end) => ends.push(end),
      });
      const app = new FakeApp({ noise, origin: ORIGIN }, (frame) => queueMicrotask(() => protocol.receive(frame)));
      protocol.start();
      await expect(protocol.established, name).rejects.toBeInstanceOf(SessionClosed);
      expect(ends, name).toEqual([{ reason: "violation", detail: error.message }]);
      await expect(protocol.request("hello", hello), name).rejects.toBeInstanceOf(SessionClosed);
    }
  });

  it("rejects an error response without ending the session", async () => {
    const { protocol, ends } = connect();
    await expect(protocol.request("unlock", {})).rejects.toEqual(new ProtocolError("unpaired"));
    await expect(protocol.request("hello", hello)).resolves.toMatchObject({ paired: false });
    expect(ends).toEqual([]);
  });

  it("fails only a request that nests deeper than the app takes, before it takes a slot or writes a frame", async () => {
    const answers: (() => void)[] = [];
    const { app, protocol, sent, ends } = connect({
      handler: (request) =>
        request.type === "generate" ? new Promise((resolve) => answers.push(() => resolve({ password: "pw" }))) : undefined,
    });
    await protocol.established;
    // Every slot taken: a request that took one would wait.
    const pending = Array.from({ length: MAX_IN_FLIGHT }, () => protocol.request("generate", {}));
    await flush();
    const frames = sent.length;
    /** Options whose arrays and objects nest [depth] deep, in a message one deeper. */
    const nested = (depth: number): JsonObject => {
      let value: unknown = 1;
      for (let i = 1; i < depth; i++) value = i % 2 === 0 ? { a: value } : [value];
      return { a: value };
    };
    const fields = (depth: number) => ({ origin: "https://example.com", options: nested(depth), same_origin_with_ancestors: true });
    await expect(protocol.request("passkey_create", fields(MAX_DEPTH))).rejects.toBeInstanceOf(MessageTooDeep);
    expect(sent).toHaveLength(frames);
    answers.splice(0).forEach((answer) => answer());
    await Promise.all(pending);
    // The session goes on, and takes the deepest message the app does.
    await expect(protocol.request("hello", hello)).resolves.toMatchObject({ protocol: 1 });
    await protocol.request("passkey_create", fields(MAX_DEPTH - 1)).catch(() => undefined);
    expect(app.requests.at(-1)).toMatchObject({ type: "passkey_create" });
    expect(ends).toEqual([]);
  });

  it("counts the nesting of arrays and objects, not of brackets in strings", () => {
    expect(nestsAtMost({}, 1)).toBe(true);
    expect(nestsAtMost({ a: [] }, 1)).toBe(false);
    expect(nestsAtMost({ a: [{}] }, 3)).toBe(true);
    expect(nestsAtMost({ a: [{ b: 1 }], c: "[[[[{{{{" }, 3)).toBe(true);
    expect(nestsAtMost({ a: [{ b: [] }] }, 3)).toBe(false);
    expect(nestsAtMost("[[[", 0)).toBe(true);
  });

  it("rejects a response of the wrong shape", async () => {
    const { protocol } = connect({ handler: () => ({ vault: "open" }) });
    await expect(protocol.request("unlock", {})).rejects.toBeInstanceOf(InvalidResponse);
  });

  it("reads password_hidden of an item as an optional boolean", async () => {
    const item = { handle: "h", name: "Example", account: "Work", has_password: true, has_totp: false, reprompt: false };
    const response = (hidden: unknown) => ({ blocked: false, save_blocked: false, truncated: false, items: [{ ...item, password_hidden: hidden }] });
    let hidden: unknown = true;
    const { protocol } = connect({ handler: (request) => (request.type === "suggest" ? response(hidden) : undefined) });
    await expect(protocol.request("suggest", { url: "https://example.com/" })).resolves.toMatchObject({
      items: [{ password_hidden: true }],
    });
    hidden = "yes";
    await expect(protocol.request("suggest", { url: "https://example.com/" })).rejects.toBeInstanceOf(InvalidResponse);
  });

  it("reassembles fragmented responses and delivers events", async () => {
    const big = "x".repeat(5000);
    const { app, protocol, events } = connect({
      maxFragmentData: 200,
      handler: (request) => (request.type === "generate" ? { password: big } : undefined),
    });
    await expect(protocol.request("generate", {})).resolves.toEqual({ id: 1, ok: true, password: big });
    app.event({ event: "vault", state: "unlocked" });
    app.event({ event: "something_new" });
    app.event({ event: "revoked", extra: 1 });
    await flush();
    expect(events).toEqual([{ event: "vault", state: "unlocked" }, { event: "revoked" }]);
  });

  it.each([
    ["current", { exchanges, events, errors }],
    ["frozen v1", frozenV1],
  ] as const)("exchanges the %s app’s messages", async (_, corpus) => {
    let reply: JsonObject = {};
    const { app, protocol, events } = connect({ handler: () => reply });
    for (const [type, { request, response }] of Object.entries(corpus.exchanges)) {
      // The session sends `cancel` itself.
      if (type === "cancel") continue;
      const { id: _responseId, ok: _ok, ...responseBody } = response;
      reply = responseBody;
      const { id: _requestId, type: _type, ...fields } = request;
      const answer = await protocol.request(type as RequestType, fields as never);
      const id = app.requests.at(-1)!.id;
      expect(app.requests.at(-1)).toEqual({ ...request, id });
      expect(answer).toEqual({ ...response, id });
    }
    for (const { error } of corpus.errors) {
      reply = { error };
      await expect(protocol.request("unlock", {})).rejects.toEqual(new ProtocolError(error as string));
    }
    for (const event of corpus.events) app.event(event);
    await flush();
    expect(events).toEqual(corpus.events);
  });

  it.each([
    ["an unknown ID", { id: 99, ok: true }],
    ["no ID", { ok: true }],
    ["a string ID", { id: "1", ok: true }],
    ["no ok", { id: 1 }],
    ["neither an ID nor an event", { hello: 1 }],
  ])("ends the session on a response with %s", async (_, message) => {
    const { app, protocol, ends } = connect({ handler: () => new Promise(() => undefined) });
    const pending = protocol.request("unlock", {});
    await flush();
    app.write(message);
    await expect(pending).rejects.toBeInstanceOf(SessionClosed);
    expect(ends[0]?.reason).toBe("violation");
  });

  it("ends the session on a message that fails to decrypt", async () => {
    const { protocol, ends } = connect();
    await protocol.established;
    protocol.receive(new Uint8Array(40));
    expect(ends[0]?.reason).toBe("violation");
  });

  it("keeps at most 8 requests in flight", async () => {
    const answers: (() => void)[] = [];
    const { app, protocol } = connect({
      handler: (request) =>
        new Promise((resolve) => answers.push(() => resolve({ password: String(request.id) }))),
    });
    const requests = Array.from({ length: MAX_IN_FLIGHT + 2 }, () => protocol.request("generate", {}));
    await flush();
    expect(app.requests).toHaveLength(MAX_IN_FLIGHT);
    answers.shift()!();
    await flush();
    expect(app.requests).toHaveLength(MAX_IN_FLIGHT + 1);
    answers.splice(0).forEach((answer) => answer());
    await flush();
    answers.splice(0).forEach((answer) => answer());
    const passwords = (await Promise.all(requests)).map((it) => it.password);
    expect(passwords).toEqual(Array.from({ length: MAX_IN_FLIGHT + 2 }, (_, i) => String(i + 1)));
    // IDs count up from 1 and are never reused within the session.
    expect(app.requests.map((it) => it.id)).toEqual(passwords.map(Number));
  });

  it("cancels a request in flight: fails it at once, and sends cancel outside the 8 slots", async () => {
    const answers: (() => void)[] = [];
    const { app, protocol, ends } = connect({
      handler: (request) =>
        new Promise((resolve) => answers.push(() => resolve({ password: String(request.id) }))),
    });
    const abort = new AbortController();
    const first = protocol.request("generate", {}, abort.signal);
    const others = Array.from({ length: MAX_IN_FLIGHT - 1 }, () => protocol.request("generate", {}));
    const queued = protocol.request("generate", {});
    await flush();
    expect(app.requests).toHaveLength(MAX_IN_FLIGHT);

    abort.abort();
    await expect(first).rejects.toEqual(new ProtocolError("cancelled"));
    await flush();
    // `cancel` went out with 8 requests in flight, and has its own ID.
    expect(app.cancels).toEqual([1]);
    // The app answered `cancelled`, which freed the slot.
    expect(app.requests.map((it) => it.id)).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 10]);
    answers.splice(1).forEach((answer) => answer());
    await expect(queued).resolves.toMatchObject({ password: "10" });
    await Promise.all(others);
    expect(ends).toEqual([]);
  });

  it("drops the answer of a cancelled request that crossed the cancel", async () => {
    const { app, protocol, ends } = connect({
      handler: (request) => (request.type === "generate" ? new Promise(() => undefined) : undefined),
    });
    const abort = new AbortController();
    const pending = protocol.request("generate", {}, abort.signal);
    await flush();
    // The app answers just before it reads the cancel.
    app.respond(1, { password: "late" });
    abort.abort();
    await expect(pending).rejects.toEqual(new ProtocolError("cancelled"));
    await flush();
    expect(app.cancels).toEqual([1]);
    expect(ends).toEqual([]);
    await expect(protocol.request("unlock", {})).rejects.toEqual(new ProtocolError("unpaired"));
  });

  it("sends nothing for a request that aborts before it's sent", async () => {
    const answers: (() => void)[] = [];
    const { app, protocol } = connect({
      handler: () => new Promise((resolve) => answers.push(() => resolve({ password: "x" }))),
    });
    const aborted = AbortSignal.abort();
    await expect(protocol.request("generate", {}, aborted)).rejects.toEqual(new ProtocolError("cancelled"));
    const full = Array.from({ length: MAX_IN_FLIGHT }, () => protocol.request("generate", {}));
    // Waiting for a slot: it leaves the queue.
    const abort = new AbortController();
    const waiting = protocol.request("generate", {}, abort.signal);
    await flush();
    abort.abort();
    await expect(waiting).rejects.toEqual(new ProtocolError("cancelled"));
    answers.splice(0).forEach((answer) => answer());
    await Promise.all(full);
    await flush();
    expect(app.requests).toHaveLength(MAX_IN_FLIGHT);
    expect(app.cancels).toEqual([]);
  });

  it("fails pending requests when closed", async () => {
    const { protocol, ends } = connect({ handler: () => new Promise(() => undefined) });
    const pending = protocol.request("unlock", {});
    await flush();
    protocol.close();
    await expect(pending).rejects.toEqual(new SessionClosed({ reason: "closed" }));
    expect(ends).toEqual([{ reason: "closed" }]);
  });

  it("fails a request after close, with 8 in flight", async () => {
    const { app, protocol } = connect({ handler: () => new Promise(() => undefined) });
    const full = Array.from({ length: MAX_IN_FLIGHT }, () => protocol.request("generate", {}).catch((error: unknown) => error));
    await flush();
    expect(app.requests).toHaveLength(MAX_IN_FLIGHT);
    protocol.close();
    const closed = new SessionClosed({ reason: "closed" });
    expect(await Promise.all(full)).toEqual(Array.from({ length: MAX_IN_FLIGHT }, () => closed));
    await expect(protocol.request("unlock", {})).rejects.toEqual(closed);
  });

  it("fails a request that waits for the handshake promise when the session closes", async () => {
    const { app, protocol } = connect({ handler: () => new Promise(() => undefined) });
    const full = Array.from({ length: MAX_IN_FLIGHT }, () => protocol.request("generate", {}).catch(() => undefined));
    await flush();
    expect(app.requests).toHaveLength(MAX_IN_FLIGHT);
    // Its `await established` resumes after the close.
    const late = protocol.request("unlock", {});
    protocol.close();
    await expect(late).rejects.toEqual(new SessionClosed({ reason: "closed" }));
    await Promise.all(full);
  });
});
