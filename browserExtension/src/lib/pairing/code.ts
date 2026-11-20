// The pairing commitment and code of protocol/README.md §8, with WebCrypto.
import { compareBytes, concatBytes, utf8 } from "../util/bytes";

export const NONCE_SIZE = 32;
const COMMIT_DOMAIN = "keyguard-browser-commit/v1\n";
const CODE_DOMAIN = "keyguard-browser-sas/v1";
const CODE_DIGITS = 6;

async function sha256(data: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", data as BufferSource));
}

/** `SHA-256("keyguard-browser-commit/v1\n" || extension_nonce)` */
export function pairingCommit(extensionNonce: Uint8Array): Promise<Uint8Array> {
  return sha256(concatBytes(utf8(COMMIT_DOMAIN), extensionNonce));
}

export interface PairingCodeInput {
  /** The handshake hash `h`. */
  handshakeHash: Uint8Array;
  extensionPublicKey: Uint8Array;
  appPublicKey: Uint8Array;
  appNonce: Uint8Array;
  extensionNonce: Uint8Array;
}

/** `"keyguard-browser-sas/v1" || first || second || app_nonce || extension_nonce` */
export function pairingCodeInfo(input: PairingCodeInput): Uint8Array {
  const [first, second] =
    compareBytes(input.extensionPublicKey, input.appPublicKey) <= 0
      ? [input.extensionPublicKey, input.appPublicKey]
      : [input.appPublicKey, input.extensionPublicKey];
  return concatBytes(utf8(CODE_DOMAIN), first, second, input.appNonce, input.extensionNonce);
}

async function hmacSha256(key: Uint8Array, data: Uint8Array): Promise<Uint8Array> {
  const hmacKey = await crypto.subtle.importKey("raw", key as BufferSource, { name: "HMAC", hash: "SHA-256" }, false, [
    "sign",
  ]);
  return new Uint8Array(await crypto.subtle.sign("HMAC", hmacKey, data as BufferSource));
}

/**
 * RFC 5869 HKDF-SHA256 with an empty input key, for at most 32 bytes. Spelled
 * out with HMAC, as browsers differ on importing an empty HKDF key.
 */
async function hkdfEmptyIkm(salt: Uint8Array, info: Uint8Array, length: number): Promise<Uint8Array> {
  const prk = await hmacSha256(salt, new Uint8Array(0));
  const block = await hmacSha256(prk, concatBytes(info, Uint8Array.of(1)));
  return block.slice(0, length);
}

/** The 6-digit code both sides show, with the HKDF output it comes from. */
export async function pairingCode(input: PairingCodeInput): Promise<{ code: string; okm: Uint8Array }> {
  const okm = await hkdfEmptyIkm(input.handshakeHash, pairingCodeInfo(input), 4);
  const value = new DataView(okm.buffer, okm.byteOffset, okm.byteLength).getUint32(0, false);
  return { code: String(value % 10 ** CODE_DIGITS).padStart(CODE_DIGITS, "0"), okm };
}
