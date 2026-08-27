const DEFAULT_PORT = 40432;
const PAIRING_SALT = "keyguard-pairing-v1";
const PAIRING_INFO = "shared-secret";
const PAIRING_CODE_LENGTH = 24;

const statusEl = document.getElementById("status");
const pairStatusEl = document.getElementById("pairstatus");

function setStatus(msg, ok) {
  statusEl.textContent = msg;
  statusEl.className = "status" + (ok === true ? " ok" : ok === false ? " err" : "");
}

function setPairStatus(msg, ok) {
  pairStatusEl.textContent = msg;
  pairStatusEl.className = "status" + (ok === true ? " ok" : ok === false ? " err" : "");
}

// ---------------------------------------------------------------------------
// Crypto — must match the agent binary (pairing.rs / crypto.rs).
// ---------------------------------------------------------------------------

function bufToBase64(buf) {
  const bytes = new Uint8Array(buf);
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin);
}

function base64ToBuf(b64) {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes.buffer;
}

// HKDF-SHA256(shared secret from pairing code).
async function deriveSharedSecret(code) {
  const ikm = new TextEncoder().encode(code.trim());
  const key = await crypto.subtle.importKey("raw", ikm, "HKDF", false, [
    "deriveBits",
  ]);
  const bits = await crypto.subtle.deriveBits(
    {
      name: "HKDF",
      hash: "SHA-256",
      salt: new TextEncoder().encode(PAIRING_SALT),
      info: new TextEncoder().encode(PAIRING_INFO),
    },
    key,
    256,
  );
  return new Uint8Array(bits);
}

async function deriveEncryptionKey(input) {
  const keyMaterial = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(input),
    "PBKDF2",
    false,
    ["deriveKey"],
  );
  return crypto.subtle.deriveKey(
    {
      name: "PBKDF2",
      salt: new TextEncoder().encode("keyguard-encryption-v1"),
      iterations: 600_000,
      hash: "SHA-256",
    },
    keyMaterial,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}

async function encryptSecret(secretBytes, password) {
  const key = await deriveEncryptionKey(password);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const cipher = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv },
    key,
    secretBytes,
  );
  return {
    iv: bufToBase64(iv.buffer),
    blob: bufToBase64(cipher),
    version: 1,
  };
}

async function decryptSecret(encrypted, password) {
  const key = await deriveEncryptionKey(password);
  const plain = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: new Uint8Array(base64ToBuf(encrypted.iv)) },
    key,
    base64ToBuf(encrypted.blob),
  );
  return new Uint8Array(plain);
}

function sendSecretToBackground(secretBytes) {
  return new Promise((resolve) => {
    chrome.runtime.sendMessage(
      { __kgSetSecret: bufToBase64(secretBytes) },
      resolve,
    );
  });
}

// ---------------------------------------------------------------------------
// Connection settings
// ---------------------------------------------------------------------------

document.getElementById("save").addEventListener("click", function () {
  const port = parseInt(document.getElementById("port").value, 10);
  if (!port || port < 1 || port > 65535) {
    setStatus("Invalid port (must be 1–65535).", false);
    return;
  }
  const mode = document.getElementById("mode").value;
  const autoSubmit = document.getElementById("autosubmit").checked;
  const theme = document.getElementById("theme").value;
  chrome.storage.local.set(
    {
      browserAgentPort: port,
      browserAgentMode: mode,
      autoSubmit: autoSubmit,
      theme: theme,
    },
    () => {
      setStatus(
        "Saved. Mode=" + mode + ", port=" + port +
          ", autoSubmit=" + autoSubmit + ", theme=" + theme + ".",
        true,
      );
    },
  );
});

document.getElementById("test").addEventListener("click", function () {
  const port = parseInt(document.getElementById("port").value, 10) || DEFAULT_PORT;
  setStatus("Connecting to ws://127.0.0.1:" + port + " …");
  let ws;
  try {
    ws = new WebSocket("ws://127.0.0.1:" + port);
  } catch (e) {
    setStatus("Cannot open socket: " + e, false);
    return;
  }
  let done = false;
  ws.onopen = function () {
    done = true;
    setStatus("Connected — the Keyguard agent is listening on port " + port + ".", true);
    setTimeout(function () {
      ws.close();
    }, 200);
  };
  ws.onerror = function () {
    if (!done) setStatus("Connection failed — is the Keyguard agent running?", false);
  };
});

document.getElementById("testquery").addEventListener("click", function () {
  setStatus("Sending a test query to the agent…");
  chrome.runtime.sendMessage(
    { type: "query", domain: "example.com", uri: "https://example.com/login" },
    function (res) {
      res = res || {};
      const n = (res.items || []).length;
      if (res.error) {
        setStatus("Query error — locked=" + !!res.locked + " error=" + res.error, false);
      } else {
        setStatus("Query done — locked=" + !!res.locked + " items=" + n + ".", true);
      }
    },
  );
});

// ---------------------------------------------------------------------------
// Pairing
// ---------------------------------------------------------------------------

document.getElementById("pair").addEventListener("click", async function () {
  const code = document.getElementById("paircode").value.trim();
  const password = document.getElementById("secret").value;
  const securityMode = document.getElementById("securitymode").value;
  if (code.length !== PAIRING_CODE_LENGTH) {
    setPairStatus("Pairing code must be exactly " + PAIRING_CODE_LENGTH + " characters.", false);
    return;
  }
  if (!password) {
    setPairStatus("Enter a PIN or password first.", false);
    return;
  }
  try {
    setPairStatus("Deriving shared secret…");
    const secretBytes = await deriveSharedSecret(code);
    const encrypted = await encryptSecret(secretBytes, password);
    await new Promise((resolve) =>
      chrome.storage.local.set(
        {
          keyguardSecretIv: encrypted.iv,
          keyguardSecretBlob: encrypted.blob,
          keyguardSecurityMode: securityMode,
        },
        resolve,
      ),
    );
    const resp = await sendSecretToBackground(secretBytes);
    if (resp && resp.ok) {
      setPairStatus("Paired! The WebSocket agent will accept this profile.", true);
    } else {
      setPairStatus("Paired and saved, but the background rejected the secret: " +
        (resp && resp.error ? resp.error : "unknown"), false);
    }
  } catch (e) {
    setPairStatus("Pairing failed: " + e, false);
  }
});

document.getElementById("unlock").addEventListener("click", async function () {
  const password = document.getElementById("secret").value;
  if (!password) {
    setPairStatus("Enter your PIN or password first.", false);
    return;
  }
  try {
    const stored = await new Promise((res) =>
      chrome.storage.local.get(["keyguardSecretIv", "keyguardSecretBlob"], res),
    );
    if (!stored.keyguardSecretBlob) {
      setPairStatus("Not paired yet — enter a pairing code and press Pair.", false);
      return;
    }
    setPairStatus("Decrypting…");
    const secretBytes = await decryptSecret(
      { iv: stored.keyguardSecretIv, blob: stored.keyguardSecretBlob },
      password,
    );
    const resp = await sendSecretToBackground(secretBytes);
    if (resp && resp.ok) {
      setPairStatus("Unlocked until the browser restarts.", true);
    } else {
      setPairStatus("Unlock failed: " + (resp && resp.error ? resp.error : "unknown"), false);
    }
  } catch (e) {
    setPairStatus("Wrong PIN or password.", false);
  }
});

document.getElementById("unpair").addEventListener("click", function () {
  chrome.storage.local.remove(
    ["keyguardSecretIv", "keyguardSecretBlob", "keyguardSecurityMode"],
    function () {
      setPairStatus("Unpaired. Stored secret removed.", true);
    },
  );
});

// ---------------------------------------------------------------------------
// Load
// ---------------------------------------------------------------------------

function load() {
  chrome.storage.local.get(
    ["browserAgentPort", "browserAgentMode", "keyguardSecretBlob", "autoSubmit", "theme"],
    function (s) {
      document.getElementById("port").value = s.browserAgentPort ?? DEFAULT_PORT;
      document.getElementById("mode").value = s.browserAgentMode || "auto";
      document.getElementById("autosubmit").checked = !!s.autoSubmit;
      document.getElementById("theme").value = s.theme || "system";
      if (s.keyguardSecretBlob) {
        setPairStatus("Paired (locked). Unlock after a browser restart.", null);
      }
    },
  );
}

load();
