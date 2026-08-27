// ---------------------------------------------------------------------------
// Logger — ring buffer shared across all extension contexts.
// ---------------------------------------------------------------------------

const LOG_MAX = 3000;
const LOG = [];

/**
 * Append a log entry to the ring buffer, broadcast it to other contexts
 * (popup, options), and write to the console.
 */
export function logBg(level, msg, extra) {
  const entry = {
    ts: new Date().toISOString(),
    level,
    msg: String(msg),
    extra: extra === undefined ? undefined : String(extra),
  };
  LOG.push(entry);
  if (LOG.length > LOG_MAX) LOG.shift();
  try {
    chrome.runtime.sendMessage({ __kgLog: entry, __kgFrom: "bg" });
  } catch (e) {}
  try {
    console.log(`[kg:${level}] ${entry.msg}${entry.extra ? " " + entry.extra : ""}`);
  } catch (e) {}
}

/** Return the full log buffer (used by the debug page). */
export function getLogs() {
  return LOG;
}

/** Clear the log buffer. */
export function clearLogs() {
  LOG.length = 0;
}

/** Push a raw entry into the buffer (used by cross-context log relay). */
export function pushLog(entry) {
  LOG.push(entry);
  if (LOG.length > LOG_MAX) LOG.shift();
}
