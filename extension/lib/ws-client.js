// WebSocket client for Safari mode — ECDH + AES-256-GCM + HMAC challenge-response.

import { logBg } from "./logger.js";
import { bufToBase64, b64ToBytes } from "./base64.js";
import { generateKeyPair, deriveAesKey, encryptJson, decryptJson } from "./crypto.js";
import { importHmacKey, hmacSign } from "./hmac.js";

const DEFAULT_PORT = 40432;
const HANDSHAKE_TIMEOUT_MS = 8000;
const REQUEST_TIMEOUT_MS = 10_000;

let ws = null;
let aesKey = null;
let clientId = null;
let handshakePromise = null;
let sharedSecretBytes = null;

// Map<requestId, { resolve, timer }> for in-flight requests.
let pendingRequests = new Map();
let nextReqId = 0;

export function setSharedSecret(b64) {
  try {
    sharedSecretBytes = b64ToBytes(b64);
    logBg("info", "Shared secret loaded into memory");
    return { ok: true };
  } catch (e) {
    logBg("error", "Failed to load shared secret", String(e));
    return { ok: false, error: String(e) };
  }
}

export function isPaired() {
  return !!sharedSecretBytes;
}

function getPort() {
  return new Promise((resolve) => {
    let done = false;
    const finish = (p) => {
      if (done) return;
      done = true;
      const port = typeof p === "number" && p >= 1 && p <= 65535 ? p : DEFAULT_PORT;
      resolve(port);
    };
    try {
      const r = chrome.storage.local.get("browserAgentPort", (stored) => {
        finish(stored && stored.browserAgentPort);
      });
      if (r && typeof r.then === "function") {
        r.then((stored) => finish(stored && stored.browserAgentPort)).catch(() => finish(DEFAULT_PORT));
      }
    } catch (e) {
      finish(DEFAULT_PORT);
    }
  });
}

function wsConnect() {
  if (ws && ws.readyState === WebSocket.OPEN && aesKey) {
    logBg("debug", "WS: reuse existing handshaked socket");
    return Promise.resolve();
  }
  if (handshakePromise) {
    logBg("debug", "WS: handshake already in progress, sharing promise");
    return handshakePromise;
  }

  handshakePromise = new Promise((resolve, reject) => {
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      handshakePromise = null;
      try { ws && ws.close(); } catch (_) {}
      logBg("error", "handshake timeout");
      reject(new Error("handshake timeout"));
    }, HANDSHAKE_TIMEOUT_MS);

    getPort()
      .then((port) => {
        logBg("info", "WS: connecting", `ws://127.0.0.1:${port}`);
        ws = new WebSocket(`ws://127.0.0.1:${port}`);

        ws.onopen = async () => {
          try {
            const { privateKey, publicBytes } = await generateKeyPair();
            clientId = crypto.randomUUID();
            ws._privateKey = privateKey;
            const hello = JSON.stringify({
              client_id: clientId,
              public_key: bufToBase64(publicBytes),
            });
            ws.send(hello);
          } catch (e) {
            settled = true;
            clearTimeout(timer);
            handshakePromise = null;
            reject(e);
          }
        };

        ws.onmessage = async (event) => {
          try {
            const msg = JSON.parse(event.data);

            if (msg.public_key && !aesKey) {
              aesKey = await deriveAesKey(ws._privateKey, b64ToBytes(msg.public_key), clientId);
              logBg("info", "WS: ECDH complete");
              return;
            }

            if (msg.type === "hmac_challenge") {
              const challengeBytes = b64ToBytes(msg.challenge);
              if (!sharedSecretBytes) {
                settled = true;
                clearTimeout(timer);
                handshakePromise = null;
                ws.close();
                reject(new Error("Locked — open the extension options to pair or unlock"));
                return;
              }
              const hmacKey = await importHmacKey(sharedSecretBytes);
              const hmacSig = await hmacSign(hmacKey, challengeBytes);
              const env = await encryptJson(aesKey, {
                type: "hmac_response",
                response: bufToBase64(hmacSig),
              });
              ws.send(JSON.stringify(env));
              return;
            }

            if (msg.type === "hmac_ok") {
              if (settled) return;
              settled = true;
              clearTimeout(timer);
              handshakePromise = null;
              logBg("info", "WS: HMAC verified");
              resolve();
              return;
            }

            if (msg.type === "hmac_failed") {
              settled = true;
              clearTimeout(timer);
              handshakePromise = null;
              ws.close();
              reject(new Error("HMAC verification failed"));
              return;
            }

            // Phase 3: Encrypted responses — routed by request ID.
            if (aesKey && msg.nonce && msg.payload) {
              const inner = await decryptJson(aesKey, msg.nonce, msg.payload);
              logBg("debug", "WS: response type", inner.type || "(none)");
              const reqId = inner._reqId;
              if (reqId != null && pendingRequests.has(reqId)) {
                const { resolve: res, timer: reqTimer } = pendingRequests.get(reqId);
                clearTimeout(reqTimer);
                pendingRequests.delete(reqId);
                res(inner);
              }
              return;
            }
          } catch (e) {
            logBg("error", "WS: onmessage error", String(e));
          }
        };

        ws.onclose = () => {
          logBg("warn", "WS: closed");
          aesKey = null;
          // Reject all pending requests.
          for (const [reqId, { reject: rej, timer: reqTimer }] of pendingRequests) {
            clearTimeout(reqTimer);
            rej(new Error("ws closed"));
          }
          pendingRequests.clear();
          if (!settled) {
            settled = true;
            clearTimeout(timer);
            handshakePromise = null;
            reject(new Error("ws closed before handshake"));
          }
        };

        ws.onerror = (e) => {
          logBg("error", "WS: error", e && e.message ? e.message : "unknown");
          if (!settled) {
            settled = true;
            clearTimeout(timer);
            handshakePromise = null;
            reject(new Error("ws error (is the Keyguard agent running?)"));
          }
        };
      })
      .catch((e) => {
        settled = true;
        clearTimeout(timer);
        handshakePromise = null;
        reject(e);
      });
  });

  return handshakePromise;
}

export async function wsRequest(obj) {
  await wsConnect();
  if (!aesKey) throw new Error("handshake not complete");
  const reqId = nextReqId++;
  const env = await encryptJson(aesKey, { ...obj, _reqId: reqId });
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pendingRequests.delete(reqId);
      reject(new Error("ws request timeout"));
    }, REQUEST_TIMEOUT_MS);
    pendingRequests.set(reqId, { resolve, reject, timer });
    ws.send(JSON.stringify(env));
  });
}

export function isWsConnected() {
  return !!(ws && ws.readyState === WebSocket.OPEN && aesKey);
}
