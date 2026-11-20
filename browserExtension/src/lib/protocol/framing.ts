// Plaintext framing inside Noise transport messages, protocol/README.md §3: a
// flags byte, then up to 65,518 bytes of a UTF-8 JSON object of at most 1 MiB,
// split over at most 32 fragments, whose arrays and objects nest at most 64
// deep.
import { MAX_TRANSPORT_PAYLOAD_SIZE } from "../crypto/noise";
import { concatBytes } from "../util/bytes";
import { isObject } from "../util/json";

/** Bit 0 of the flags byte: more fragments of this message follow. */
export const FLAG_MORE = 0x01;
/** The largest data of one fragment: a transport payload minus the flags byte. */
export const MAX_FRAGMENT_DATA = MAX_TRANSPORT_PAYLOAD_SIZE - 1;
/** The largest message, in bytes of UTF-8 JSON. */
export const MAX_MESSAGE_SIZE = 1_048_576;
/** The most fragments of one message. */
export const MAX_FRAGMENTS = 32;
/** How deep the arrays and objects of a message nest at most, the message's own object counting as 1. */
export const MAX_DEPTH = 64;

export type JsonObject = Record<string, unknown>;

/** A peer broke the framing or message rules; the session must end. */
export class ProtocolViolation extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ProtocolViolation";
  }
}

/** A message this side can't send: too large for the framing. */
export class MessageTooLarge extends Error {
  constructor(size: number) {
    super(`A message of ${size} bytes is too large`);
    this.name = "MessageTooLarge";
  }
}

/**
 * A message this side can't send: its arrays and objects nest deeper than
 * [MAX_DEPTH]. The app would end the session, and every request with it.
 */
export class MessageTooDeep extends Error {
  constructor() {
    super(`A message nests deeper than ${MAX_DEPTH}`);
    this.name = "MessageTooDeep";
  }
}

/** Whether the arrays and objects of the JSON [value] nest at most [maxDepth] deep, [value] itself counting as 1. */
export function nestsAtMost(value: unknown, maxDepth: number): boolean {
  if (typeof value !== "object" || value === null) return true;
  if (maxDepth < 1) return false;
  const children: unknown[] = Array.isArray(value) ? value : Object.values(value);
  return children.every((child) => nestsAtMost(child, maxDepth - 1));
}

/**
 * Splits `message` into the plaintexts of transport messages. `maxData` exists
 * for tests; senders use [MAX_FRAGMENT_DATA].
 */
export function fragmentMessage(message: JsonObject, maxData: number = MAX_FRAGMENT_DATA): Uint8Array[] {
  const data = new TextEncoder().encode(JSON.stringify(message));
  const count = Math.max(1, Math.ceil(data.length / maxData));
  if (data.length > MAX_MESSAGE_SIZE || count > MAX_FRAGMENTS) throw new MessageTooLarge(data.length);
  const fragments: Uint8Array[] = [];
  for (let i = 0; i < count; i++) {
    const chunk = data.subarray(i * maxData, Math.min(data.length, (i + 1) * maxData));
    const fragment = new Uint8Array(chunk.length + 1);
    fragment[0] = i < count - 1 ? FLAG_MORE : 0;
    fragment.set(chunk, 1);
    fragments.push(fragment);
  }
  return fragments;
}

/** Joins incoming fragments into messages, enforcing every §3 rule. */
export class Reassembler {
  private parts: Uint8Array[] = [];
  private size = 0;

  /**
   * Takes the plaintext of the next transport message. Returns the message it
   * completes, or `null` if more fragments follow. Throws [ProtocolViolation].
   */
  push(plaintext: Uint8Array): JsonObject | null {
    if (plaintext.length < 1) throw new ProtocolViolation("A fragment has no flags byte");
    const flags = plaintext[0]!;
    if ((flags & ~FLAG_MORE) !== 0) throw new ProtocolViolation("A fragment sets a reserved flag");
    const data = plaintext.subarray(1);
    if (data.length === 0) throw new ProtocolViolation("A fragment is empty");
    if (data.length > MAX_FRAGMENT_DATA) throw new ProtocolViolation("A fragment is too large");
    if (this.parts.length >= MAX_FRAGMENTS) throw new ProtocolViolation("A message has too many fragments");
    if (this.size + data.length > MAX_MESSAGE_SIZE) throw new ProtocolViolation("A message is too large");
    this.parts.push(data.slice());
    this.size += data.length;
    if ((flags & FLAG_MORE) !== 0) return null;

    const joined = concatBytes(...this.parts);
    this.parts = [];
    this.size = 0;
    return parseObject(joined);
  }
}

function parseObject(bytes: Uint8Array): JsonObject {
  let text: string;
  try {
    // ignoreBOM keeps a leading BOM, which JSON then rejects.
    text = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(bytes);
  } catch {
    throw new ProtocolViolation("A message isn't UTF-8");
  }
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    throw new ProtocolViolation("A message isn't JSON");
  }
  if (!isObject(value)) throw new ProtocolViolation("A message isn't a JSON object");
  return value;
}
