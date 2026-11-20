import { describe, expect, it } from "vitest";
import {
  FLAG_MORE,
  fragmentMessage,
  MAX_FRAGMENT_DATA,
  MAX_FRAGMENTS,
  MAX_MESSAGE_SIZE,
  MessageTooLarge,
  ProtocolViolation,
  Reassembler,
} from "../src/lib/protocol/framing";
import { concatBytes, fromHex, utf8 } from "../src/lib/util/bytes";
import { vectors } from "./support/vectors";

function fragment(flags: number, data: Uint8Array | string): Uint8Array {
  return concatBytes(Uint8Array.of(flags), typeof data === "string" ? utf8(data) : data);
}

describe("fragmentMessage", () => {
  it("writes a small message as one fragment with a clear flags byte", () => {
    const [only, ...rest] = fragmentMessage({ id: 1, type: "status" });
    expect(rest).toHaveLength(0);
    expect(only).toEqual(fragment(0, '{"id":1,"type":"status"}'));
  });

  it("matches the framed plaintexts of the vectors", () => {
    const hello = vectors.keyguard.messages[3]!;
    const [only] = fragmentMessage(JSON.parse(hello.text!));
    expect(only).toEqual(fromHex(hello.payload));
  });

  it("splits at any byte, also inside a UTF-8 sequence, and marks all but the last", () => {
    const message = { text: "ключ🔑".repeat(10) };
    const fragments = fragmentMessage(message, 5);
    expect(fragments.length).toBeGreaterThan(2);
    fragments.forEach((it, index) => {
      expect(it[0]).toBe(index < fragments.length - 1 ? FLAG_MORE : 0);
      expect(it.length).toBeLessThanOrEqual(6);
    });
    const reassembler = new Reassembler();
    const results = fragments.map((it) => reassembler.push(it));
    expect(results.slice(0, -1).every((it) => it === null)).toBe(true);
    expect(results.at(-1)).toEqual(message);
  });

  it("uses at most 65,518 bytes of data per fragment", () => {
    const fragments = fragmentMessage({ text: "a".repeat(MAX_FRAGMENT_DATA * 2) });
    expect(fragments).toHaveLength(3);
    expect(fragments[0]!.length).toBe(MAX_FRAGMENT_DATA + 1);
    expect(MAX_FRAGMENT_DATA).toBe(65_518);
  });

  it("refuses a message over 1 MiB or over 32 fragments", () => {
    expect(() => fragmentMessage({ text: "a".repeat(MAX_MESSAGE_SIZE) })).toThrow(MessageTooLarge);
    expect(() => fragmentMessage({ text: "a".repeat(400) }, 10)).toThrow(MessageTooLarge);
  });
});

describe("Reassembler", () => {
  it("parses the app's responses of the vectors", () => {
    const response = vectors.keyguard.messages[4]!;
    expect(new Reassembler().push(fromHex(response.payload))).toEqual(JSON.parse(response.text!));
  });

  it("accepts a 1 MiB message in 32 fragments", () => {
    const text = '{"a":"' + "x".repeat(MAX_MESSAGE_SIZE - 8) + '"}';
    const data = utf8(text);
    expect(data.length).toBe(MAX_MESSAGE_SIZE);
    const size = Math.ceil(data.length / MAX_FRAGMENTS);
    const reassembler = new Reassembler();
    let result = null;
    for (let i = 0; i < MAX_FRAGMENTS; i++) {
      const last = i === MAX_FRAGMENTS - 1;
      result = reassembler.push(fragment(last ? 0 : FLAG_MORE, data.subarray(i * size, (i + 1) * size)));
    }
    expect(result).toEqual(JSON.parse(text));
  });

  const violations: [string, Uint8Array[]][] = [
    ["a missing flags byte", [new Uint8Array(0)]],
    ["a reserved flag", [fragment(0x02, "{}")]],
    ["a reserved flag next to the more bit", [fragment(0x81, "{}")]],
    ["an empty fragment", [Uint8Array.of(0)]],
    ["an empty fragment in the middle", [fragment(FLAG_MORE, "{"), Uint8Array.of(FLAG_MORE)]],
    ["a 33rd fragment", Array.from({ length: 33 }, () => fragment(FLAG_MORE, " "))],
    ["an oversized message", Array.from({ length: 17 }, () => fragment(FLAG_MORE, new Uint8Array(MAX_FRAGMENT_DATA)))],
    ["an oversized fragment", [fragment(0, new Uint8Array(MAX_FRAGMENT_DATA + 1))]],
    ["invalid UTF-8", [fragment(0, Uint8Array.of(0x7b, 0xff, 0x7d))]],
    ["a byte order mark", [fragment(0, concatBytes(Uint8Array.of(0xef, 0xbb, 0xbf), utf8("{}")))]],
    ["text that isn't JSON", [fragment(0, "{id:1}")]],
    ["a JSON array", [fragment(0, "[1]")]],
    ["a JSON string", [fragment(0, '"{}"')]],
    ["JSON null", [fragment(0, "null")]],
  ];

  it.each(violations)("ends the session on %s", (_, fragments) => {
    const reassembler = new Reassembler();
    expect(() => fragments.forEach((it) => reassembler.push(it))).toThrow(ProtocolViolation);
  });
});
