// ---------------------------------------------------------------------------
// HMAC-SHA256 via Web Crypto.
// ---------------------------------------------------------------------------

/**
 * Import raw bytes as an HMAC-SHA256 key.
 */
export async function importHmacKey(secretBytes) {
  return crypto.subtle.importKey(
    "raw",
    secretBytes,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign", "verify"],
  );
}

/**
 * Compute HMAC-SHA256(key, data) and return the signature as Uint8Array.
 */
export async function hmacSign(key, data) {
  const sig = await crypto.subtle.sign("HMAC", key, data);
  return new Uint8Array(sig);
}
