// The CRX3 writer (scripts/crx.ts). Parses its output with the protobuf
// runtime's reader, not the writer's encoder, and checks the CRX ID and the
// signature as Chrome does.
import { createHash, createPublicKey, generateKeyPairSync, verify } from "node:crypto";
import { BinaryReader, WireType } from "@bufbuild/protobuf/wire";
import { describe, expect, it } from "vitest";
import { crx3, publicKeyPem } from "../scripts/crx";

const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const SPKI = publicKey.export({ type: "spki", format: "der" });

/** The length-delimited fields of a protobuf message, by field number. */
function fields(message: Uint8Array): Map<number, Uint8Array[]> {
  const result = new Map<number, Uint8Array[]>();
  const reader = new BinaryReader(message);
  while (reader.pos < reader.len) {
    const [number, wireType] = reader.tag();
    expect(wireType).toBe(WireType.LengthDelimited);
    result.set(number, [...(result.get(number) ?? []), reader.bytes()]);
  }
  return result;
}

function only(map: Map<number, Uint8Array[]>, number: number): Buffer {
  const values = map.get(number) ?? [];
  expect(values).toHaveLength(1);
  return Buffer.from(values[0]!);
}

/** The parts of a CRX3 file, read as Chrome's verifier does. */
function parse(crx: Buffer) {
  expect(crx.subarray(0, 4).toString("latin1")).toBe("Cr24");
  expect(crx.readUInt32LE(4)).toBe(3);
  const headerLength = crx.readUInt32LE(8);
  const header = fields(crx.subarray(12, 12 + headerLength));
  expect([...header.keys()].sort((a, b) => a - b)).toEqual([2, 10000]);
  const proof = fields(only(header, 2));
  const signedHeaderData = only(header, 10000);
  return {
    publicKey: only(proof, 1),
    signature: only(proof, 2),
    signedHeaderData,
    crxId: only(fields(signedHeaderData), 1),
    archive: crx.subarray(12 + headerLength),
  };
}

// A zip's bytes; the writer doesn't read them. More than 127 bytes, for
// lengths of more than one varint byte.
const ZIP = Buffer.concat([Buffer.from("PK\x03\x04"), Buffer.alloc(300, 0x5a)]);

describe("crx3", () => {
  it("writes the zip after a header that Chrome verifies", () => {
    const crx = parse(crx3(ZIP, privateKey));
    expect(crx.archive.equals(ZIP)).toBe(true);
    expect(crx.publicKey.equals(SPKI)).toBe(true);
    // The CRX ID is the first 16 bytes of the SHA-256 of the key's SPKI.
    expect(crx.crxId.equals(createHash("sha256").update(SPKI).digest().subarray(0, 16))).toBe(true);
    const length = Buffer.alloc(4);
    length.writeUInt32LE(crx.signedHeaderData.length);
    const signed = Buffer.concat([Buffer.from("CRX3 SignedData\x00"), length, crx.signedHeaderData, ZIP]);
    const key = createPublicKey({ key: crx.publicKey, format: "der", type: "spki" });
    expect(verify("sha256", signed, key, crx.signature)).toBe(true);
    // A changed zip fails the check.
    const tampered = Buffer.concat([signed.subarray(0, -1), Buffer.from([0])]);
    expect(verify("sha256", tampered, key, crx.signature)).toBe(false);
  });

  it("encodes the header's fields as Chrome's crx3.proto does", () => {
    const crx = crx3(ZIP, privateKey);
    const headerEnd = 12 + crx.readUInt32LE(8);
    // sha256_with_rsa = 2: the tag, then a length of two varint bytes.
    expect(crx[12]).toBe(0x12);
    expect(crx[13]! & 0x80).toBe(0x80);
    // signed_header_data = 10000: the tag, the length 18, then crx_id = 1 of
    // 16 bytes.
    expect([...crx.subarray(headerEnd - 22, headerEnd - 16)]).toEqual([0x82, 0xf1, 0x04, 0x12, 0x0a, 0x10]);
  });

  it("refuses a key that isn't RSA", () => {
    const { privateKey: ecKey } = generateKeyPairSync("ec", { namedCurve: "P-256" });
    expect(() => crx3(ZIP, ecKey)).toThrow("The CRX key must be an RSA key, not ec");
  });
});

describe("publicKeyPem", () => {
  it("prints the signing key's public key as PEM", () => {
    const pem = publicKeyPem(privateKey);
    expect(pem).toMatch(/^-----BEGIN PUBLIC KEY-----\n/);
    const key = createPublicKey(pem);
    expect(key.type).toBe("public");
    expect(key.export({ type: "spki", format: "der" }).equals(SPKI)).toBe(true);
  });
});
