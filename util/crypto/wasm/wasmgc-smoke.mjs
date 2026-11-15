const started = performance.now();
const worker = new Worker(new URL('../build/wasm/browser-probe/worker.mjs', import.meta.url), { type: 'module' });
worker.onmessage = ({ data }) => {
    globalThis.cryptoWasmGcResult = { ...data, totalMs: performance.now() - started };
    document.body.textContent = JSON.stringify(globalThis.cryptoWasmGcResult, null, 2);
    worker.terminate();
};
worker.onerror = event => {
    globalThis.cryptoWasmGcResult = { passed: false, error: event.message };
    document.body.textContent = JSON.stringify(globalThis.cryptoWasmGcResult);
    worker.terminate();
};
