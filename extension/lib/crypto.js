// ---------------------------------------------------------------------------
// X25519 key agreement + AES-256-GCM encrypt/decrypt.
// Uses native Web Crypto — Chrome 133+, Firefox 130+, Safari TP 211+.
// ---------------------------------------------------------------------------

import { bufToBase64, base64ToBuf } from "./base64.js";

const DOMAIN_SEPARATOR = "keyguard-browser-agent-v1";

// ---------------------------------------------------------------------------
// X25519 ECDH
// ---------------------------------------------------------------------------

/**
 * Generate an ephemeral X25519 keypair.
 * @returns {{ privateKey: CryptoKey, publicBytes: Uint8Array }}
 */
export async function generateKeyPair() {
  const keyPair = await crypto.subtle.generateKey("X25519", true, [
    "deriveKey",
    "deriveBits",
  ]);
  const rawPub = new Uint8Array(
    await crypto.subtle.exportKey("raw", keyPair.publicKey),
  );
  return { privateKey: keyPair.privateKey, publicBytes: rawPub };
}

/**
 * Derive an AES-256-GCM key from an X25519 key agreement.
 *
 * The raw shared secret is mixed with a domain separator and client ID
 * via SHA-256 to stay compatible with the Rust agent's derivation.
 *
 * @param {CryptoKey} privateKey  Our ephemeral X25519 private key.
 * @param {Uint8Array} peerPublicBytes  Peer's raw 32-byte public key.
 * @param {string} clientId  Unique per-session client identifier.
 * @returns {Promise<CryptoKey>}  AES-256-GCM CryptoKey.
 */
export async function deriveAesKey(privateKey, peerPublicBytes, clientId) {
  const peerPublicKey = await crypto.subtle.importKey(
    "raw",
    peerPublicBytes,
    "X25519",
    false,
    [],
  );

  // Raw 32-byte X25519 shared secret.
  const rawShared = new Uint8Array(
    await crypto.subtle.deriveBits(
      { name: "X25519", public: peerPublicKey },
      privateKey,
      256,
    ),
  );

  // KDF: SHA-256(shared || domain_separator || client_id)
  const sep = new TextEncoder().encode(DOMAIN_SEPARATOR);
  const cid = new TextEncoder().encode(clientId);
  const material = new Uint8Array(rawShared.length + sep.length + cid.length);
  material.set(rawShared, 0);
  material.set(sep, rawShared.length);
  material.set(cid, rawShared.length + sep.length);
  const keyBytes = await crypto.subtle.digest("SHA-256", material);

  return crypto.subtle.importKey(
    "raw",
    keyBytes,
    { name: "AES-GCM" },
    false,
    ["encrypt", "decrypt"],
  );
}

// ---------------------------------------------------------------------------
// AES-256-GCM encrypt / decrypt (JSON envelope)
// ---------------------------------------------------------------------------

/**
 * Encrypt a JS object as AES-256-GCM and return the nonce + ciphertext
 * as base-64 strings suitable for JSON transport.
 */
export async function encryptJson(key, obj) {
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const data = new TextEncoder().encode(JSON.stringify(obj));
  const cipher = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: nonce },
    key,
    data,
  );
  return { nonce: bufToBase64(nonce.buffer), payload: bufToBase64(cipher) };
}

/**
 * Decrypt an AES-256-GCM envelope produced by {@link encryptJson}.
 */
export async function decryptJson(key, nonceB64, payloadB64) {
  const plain = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: new Uint8Array(base64ToBuf(nonceB64)) },
    key,
    base64ToBuf(payloadB64),
  );
  return JSON.parse(new TextDecoder().decode(plain));
}
