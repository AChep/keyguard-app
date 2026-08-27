// ---------------------------------------------------------------------------
// Native Messaging client (Firefox / Chrome / Edge).
// Uses chrome.runtime.connectNative() → stdin/stdout with 4-byte LE
// length-prefixed JSON.
// ---------------------------------------------------------------------------

import { logBg } from "./logger.js";

const NM_HOST = "com.artemchep.keyguard_agent";

let nmPort = null;
let nmConnecting = false;
let nmConnectPromise = null;
let nmPendingRequests = [];

/**
 * Open (or reuse) a native messaging port to the agent binary.
 */
export function nmConnect() {
  if (nmPort) {
    logBg("debug", "NM: reusing existing port");
    return Promise.resolve(nmPort);
  }
  if (nmConnectPromise) {
    logBg("debug", "NM: connection in progress, waiting");
    return nmConnectPromise;
  }
  nmConnecting = true;
  nmConnectPromise = new Promise((resolve, reject) => {
    logBg("info", "NM: connectNative", NM_HOST);
    try {
      nmPort = chrome.runtime.connectNative(NM_HOST);
    } catch (e) {
      nmConnecting = false;
      nmConnectPromise = null;
      reject(new Error("connectNative failed: " + (e.message || e)));
      return;
    }

    nmPort.onMessage.addListener((msg) => {
      logBg("debug", "NM: received", JSON.stringify(msg).slice(0, 200));
      const pending = nmPendingRequests.shift();
      if (pending) pending.resolve(msg);
    });

    nmPort.onDisconnect.addListener(() => {
      const err = chrome.runtime.lastError;
      logBg("warn", "NM: disconnected", err ? err.message : "");
      nmPort = null;
      nmConnecting = false;
      nmConnectPromise = null;
      while (nmPendingRequests.length) {
        const p = nmPendingRequests.shift();
        p.reject(new Error(err ? err.message : "NM disconnected"));
      }
    });

    nmConnecting = false;
    nmConnectPromise = null;
    resolve(nmPort);
  });
  return nmConnectPromise;
}

/**
 * Send a request object over Native Messaging and await the response.
 */
export function nmRequest(obj) {
  return new Promise((resolve, reject) => {
    nmConnect()
      .then((port) => {
        logBg("debug", "NM: sending", JSON.stringify(obj).slice(0, 200));
        nmPendingRequests.push({ resolve, reject });
        port.postMessage(obj);
      })
      .catch(reject);
  });
}
