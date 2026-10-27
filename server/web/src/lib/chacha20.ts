// ChaCha20 block function (RFC 8439, section 2.3). The home page background
// draws its glyphs from a genuine keystream; nothing here encrypts user data.

const SIGMA = [0x61707865, 0x3320646e, 0x79622d32, 0x6b206574]; // "expand 32-byte k"

const rotl = (v: number, n: number) => (v << n) | (v >>> (32 - n));

// Uint32Array stores wrap modulo 2^32, which gives the cipher's additions.
function quarterRound(x: Uint32Array, a: number, b: number, c: number, d: number) {
  x[a] += x[b];
  x[d] = rotl(x[d] ^ x[a], 16);
  x[c] += x[d];
  x[b] = rotl(x[b] ^ x[c], 12);
  x[a] += x[b];
  x[d] = rotl(x[d] ^ x[a], 8);
  x[c] += x[d];
  x[b] = rotl(x[b] ^ x[c], 7);
}

/** Writes the 64-byte block for an 8-word key, a counter and a 3-word nonce into `out`. */
export function chacha20Block(key: Uint32Array, counter: number, nonce: Uint32Array, out: Uint8Array): void {
  const state = new Uint32Array(16);
  state.set(SIGMA, 0);
  state.set(key, 4);
  state[12] = counter;
  state.set(nonce, 13);

  const x = state.slice();
  for (let i = 0; i < 10; i++) {
    // Column rounds, then diagonal rounds.
    quarterRound(x, 0, 4, 8, 12);
    quarterRound(x, 1, 5, 9, 13);
    quarterRound(x, 2, 6, 10, 14);
    quarterRound(x, 3, 7, 11, 15);
    quarterRound(x, 0, 5, 10, 15);
    quarterRound(x, 1, 6, 11, 12);
    quarterRound(x, 2, 7, 8, 13);
    quarterRound(x, 3, 4, 9, 14);
  }

  // Serialize little-endian.
  for (let i = 0; i < 16; i++) {
    const v = (x[i] + state[i]) >>> 0;
    out[i * 4] = v & 0xff;
    out[i * 4 + 1] = (v >>> 8) & 0xff;
    out[i * 4 + 2] = (v >>> 16) & 0xff;
    out[i * 4 + 3] = v >>> 24;
  }
}

/** Returns `length` keystream bytes, starting at block `counter`. */
export function chacha20Keystream(key: Uint32Array, nonce: Uint32Array, counter: number, length: number): Uint8Array {
  const blocks = Math.ceil(length / 64);
  const out = new Uint8Array(blocks * 64);
  for (let i = 0; i < blocks; i++) {
    chacha20Block(key, (counter + i) >>> 0, nonce, out.subarray(i * 64, i * 64 + 64));
  }
  return out.subarray(0, length);
}
