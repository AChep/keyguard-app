// Packs the Chromium zip into a CRX3 file for the Chrome Web Store's verified
// CRX uploads: the store takes only a CRX signed with the key on file. Writes
// the format itself with node:crypto, so the key goes through no third-party
// package. Reads the PEM private key, RSA in PKCS#8 or PKCS#1, from
// CWS_CRX_PRIVATE_KEY; the key is never written to disk. Then prints the
// key's public half, PEM (SPKI), to compare with the one in Package >
// Verified CRX Uploads; it's public, so the log may show it.
// `node scripts/crx.ts <in.zip> <out.crx>`
// https://source.chromium.org/chromium/chromium/src/+/main:components/crx_file/crx3.proto
import { createHash, createPublicKey, sign, type KeyObject } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { fail } from "./cli.ts";
import { privateKeyFromPem } from "./cws.ts";

/**
 * The CRX3 file of [zip] signed with the RSA [privateKey]: `Cr24`, the format
 * version 3 and the header's length as uint32le, the CrxFileHeader, then the
 * zip. Like Chrome's packer, the CRX ID is that of the signing key.
 */
export function crx3(zip: Uint8Array, privateKey: KeyObject): Buffer<ArrayBuffer> {
  if (privateKey.asymmetricKeyType !== "rsa") {
    throw new Error(`The CRX key must be an RSA key, not ${privateKey.asymmetricKeyType}`);
  }
  const publicKey = createPublicKey(privateKey).export({ type: "spki", format: "der" });
  // SignedData { crx_id = 1 }: the first 16 bytes of the key's SHA-256.
  const crxId = createHash("sha256").update(publicKey).digest().subarray(0, 16);
  const signedHeaderData = field(1, crxId);
  // Chrome verifies the signature over the signed header data and the zip.
  const signature = sign(
    "sha256",
    Buffer.concat([Buffer.from("CRX3 SignedData\x00"), uint32le(signedHeaderData.length), signedHeaderData, zip]),
    privateKey,
  );
  // CrxFileHeader { sha256_with_rsa = 2: AsymmetricKeyProof { public_key = 1, signature = 2 }, signed_header_data = 10000 }.
  const header = Buffer.concat([
    field(2, Buffer.concat([field(1, publicKey), field(2, signature)])),
    field(10000, signedHeaderData),
  ]);
  return Buffer.concat([Buffer.from("Cr24"), uint32le(3), uint32le(header.length), header, zip]);
}

/**
 * The public key of [privateKey] as PEM (SPKI), what
 * `openssl rsa -pubout` prints.
 */
export function publicKeyPem(privateKey: KeyObject): string {
  return createPublicKey(privateKey).export({ type: "spki", format: "pem" }) as string;
}

/** A protobuf field of wire type 2 (length-delimited): the tag, the length, the bytes. */
function field(number: number, bytes: Uint8Array): Buffer {
  return Buffer.concat([varint((number << 3) | 2), varint(bytes.length), bytes]);
}

/** A protobuf varint: 7 bits per byte, low bits first, the high bit set on all but the last. */
function varint(value: number): Buffer {
  const bytes: number[] = [];
  for (; value > 0x7f; value >>>= 7) bytes.push((value & 0x7f) | 0x80);
  bytes.push(value);
  return Buffer.from(bytes);
}

function uint32le(value: number): Buffer {
  const bytes = Buffer.alloc(4);
  bytes.writeUInt32LE(value);
  return bytes;
}

if (import.meta.main) {
  const [input, output] = process.argv.slice(2);
  const pem = process.env.CWS_CRX_PRIVATE_KEY;
  if (!input || !output) {
    console.error("Usage: node scripts/crx.ts <in.zip> <out.crx>");
    process.exit(2);
  }
  if (!pem) {
    console.error("Set CWS_CRX_PRIVATE_KEY to the PEM private key of the Chrome Web Store's verified CRX uploads.");
    process.exit(2);
  }
  try {
    const key = privateKeyFromPem(pem, "CWS_CRX_PRIVATE_KEY");
    await writeFile(output, crx3(await readFile(input), key));
    process.stdout.write(publicKeyPem(key));
  } catch (error) {
    fail(error);
  }
}
