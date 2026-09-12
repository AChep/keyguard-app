const LOG = [];
const LOG_MAX = 3000;

let filter = "all";
let autoscroll = true;

const logEl = document.getElementById("log");
const statusEl = document.getElementById("status");

function setStatus(msg, ok) {
  statusEl.textContent = msg;
  statusEl.className = "status" + (ok === true ? " ok" : ok === false ? " err" : "");
}

function fmtTime(iso) {
  const d = new Date(iso);
  if (isNaN(d)) return "";
  const p = (n, l = 2) => String(n).padStart(l, "0");
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${p(d.getMilliseconds(), 3)}`;
}

function makeRow(entry) {
  const line = document.createElement("div");
  line.className = "line" + (entry.level ? " " + entry.level : "");
  const ts = document.createElement("span");
  ts.className = "ts";
  ts.textContent = fmtTime(entry.ts);
  const lvl = document.createElement("span");
  lvl.className = "lvl " + (entry.level || "debug");
  lvl.textContent = (entry.level || "debug").slice(0, 5).toUpperCase();
  const msg = document.createElement("span");
  msg.className = "msg";
  msg.textContent = entry.msg || "";
  if (entry.extra) {
    const extra = document.createElement("span");
    extra.className = "extra";
    extra.textContent = "  " + entry.extra;
    msg.appendChild(extra);
  }
  line.append(ts, lvl, msg);
  return line;
}

function passesFilter(entry) {
  return filter === "all" || (entry.level || "debug") === filter;
}

function renderAll() {
  logEl.innerHTML = "";
  const frag = document.createDocumentFragment();
  for (const e of LOG) {
    if (passesFilter(e)) frag.appendChild(makeRow(e));
  }
  logEl.appendChild(frag);
  if (autoscroll) logEl.scrollTop = logEl.scrollHeight;
}

function appendLive(entry) {
  LOG.push(entry);
  if (LOG.length > LOG_MAX) LOG.shift();
  if (passesFilter(entry)) {
    logEl.appendChild(makeRow(entry));
    while (logEl.childElementCount > LOG_MAX) logEl.removeChild(logEl.firstChild);
    if (autoscroll) logEl.scrollTop = logEl.scrollHeight;
  }
}

// ---- live stream from background / popup / content ----
chrome.runtime.onMessage.addListener((message) => {
  if (message && message.__kgLog) {
    appendLive(message.__kgLog);
    setStatus(`Live · ${LOG.length} events buffered`);
  }
});

// ---- controls ----
document.getElementById("filters").addEventListener("click", (e) => {
  const chip = e.target.closest(".chip");
  if (!chip) return;
  filter = chip.dataset.level;
  document.querySelectorAll("#filters .chip").forEach((c) => c.classList.toggle("active", c === chip));
  renderAll();
});

document.getElementById("autoscroll").addEventListener("change", (e) => {
  autoscroll = e.target.checked;
  if (autoscroll) logEl.scrollTop = logEl.scrollHeight;
});

document.getElementById("refresh").addEventListener("click", () => {
  chrome.runtime.sendMessage({ __kgGetLogs: true }, (entries) => {
    LOG.length = 0;
    (Array.isArray(entries) ? entries : []).forEach((e) => LOG.push(e));
    renderAll();
    setStatus(`${LOG.length} events loaded from buffer`);
  });
});

document.getElementById("clear").addEventListener("click", () => {
  chrome.runtime.sendMessage({ __kgClear: true }, () => {
    LOG.length = 0;
    logEl.innerHTML = "";
    setStatus("Logs cleared");
  });
});

document.getElementById("copy").addEventListener("click", () => {
  const text = LOG.filter(passesFilter)
    .map((e) => `[${fmtTime(e.ts)}] ${(e.level || "debug").toUpperCase()} ${e.msg || ""}${e.extra ? " " + e.extra : ""}`)
    .join("\n");
  navigator.clipboard.writeText(text).then(
    () => setStatus("Logs copied to clipboard"),
    () => setStatus("Copy failed", false),
  );
});

// ---- initial load ----
chrome.runtime.sendMessage({ __kgGetLogs: true }, (entries) => {
  (Array.isArray(entries) ? entries : []).forEach((e) => LOG.push(e));
  renderAll();
  setStatus(`${LOG.length} events buffered · listening for live events…`);
});
