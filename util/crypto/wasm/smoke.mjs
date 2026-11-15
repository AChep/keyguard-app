import * as crypto from '../build/wasm/npm/index.mjs';

const hex = bytes => Array.from(bytes, byte => (byte & 255).toString(16).padStart(2, '0')).join('');
const text = value => new TextEncoder().encode(value);
function check(condition, message) {
    if (!condition) throw new Error(message);
}

// Test-only protobuf encoder for the checked-in native_crypto.proto fixtures.
function uint(value) {
    const bytes = [];
    do {
        bytes.push((value & 127) | (value > 127 ? 128 : 0));
        value >>>= 7;
    } while (value);
    return bytes;
}
const numberField = (field, value) => [...uint(field << 3), ...uint(value)];
const bytesField = (field, value) => [...uint((field << 3) | 2), ...uint(value.length), ...value];
const request = (operation, value) => Int8Array.from([...numberField(1, 3), ...bytesField(operation, value)]);

function bytesResult(response) {
    let offset = 0;
    const readUint = () => {
        let value = 0;
        let shift = 0;
        while (true) {
            const byte = response[offset++] & 255;
            value |= (byte & 127) << shift;
            if (!(byte & 128)) return value >>> 0;
            shift += 7;
            check(shift < 35 && offset < response.length, 'Invalid response varint');
        }
    };
    while (offset < response.length) {
        const tag = readUint();
        if ((tag & 7) === 0) {
            readUint();
        } else {
            check((tag & 7) === 2, 'Unexpected response field');
            const length = readUint();
            check(offset + length <= response.length, 'Truncated response');
            if (tag >>> 3 === 10) return response.slice(offset, offset + length);
            offset += length;
        }
    }
    throw new Error('Crypto operation returned no bytes');
}

async function run() {
    const started = performance.now();
    await crypto.initialize();
    const startupMs = performance.now() - started;
    check(crypto.abiVersion() === 1, 'ABI mismatch');
    const capabilities = crypto.capabilities();
    check(capabilities[0] === 0x7fffffff && capabilities[1] === 0x7fb, 'Missing capabilities');
    const draws = Array.from({ length: 32 }, () => crypto.randomInt(1000));
    check(draws.every(([status, value]) => status === 0 && value >= 0 && value < 1000), 'RNG range');
    check(new Set(draws.map(result => result[1])).size > 1, 'Constant RNG');

    const digest = bytesResult(crypto.call(request(16, [...numberField(1, 2), ...bytesField(2, text('abc'))])));
    check(hex(digest) === 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad', 'SHA-256 vector');
    const pbkdf = iterations => request(11, [
        ...bytesField(1, text('password')), ...bytesField(2, text('salt')),
        ...numberField(3, iterations), ...numberField(4, 32),
    ]);
    check(hex(bytesResult(crypto.call(pbkdf(1)))) ===
        '120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b', 'PBKDF2 vector');
    const argon = memory => request(12, [
        ...numberField(1, 3), ...bytesField(2, text('password')), ...bytesField(3, text('somesalt')),
        ...numberField(4, 1), ...numberField(5, memory), ...numberField(6, 1), ...numberField(7, 24),
    ]);
    check(hex(bytesResult(crypto.call(argon(64)))) ===
        '655ad15eac652dc59f7170a7332bf49b8469be1fdb9c28bb', 'Argon2id vector');

    // Exercise realistic KDF work and Wasm memory growth before another bridge call.
    let before = performance.now();
    check(bytesResult(crypto.call(pbkdf(600_000))).length === 32, 'PBKDF2 work factor');
    const pbkdf2Ms = performance.now() - before;
    before = performance.now();
    check(bytesResult(crypto.call(argon(65_536))).length === 24, 'Argon2 64 MiB');
    const argon2Ms = performance.now() - before;

    const encryptionKey = Int8Array.from({ length: 32 }, (_, index) => index);
    const macKey = Int8Array.from({ length: 32 }, (_, index) => index + 32);
    const iv = Int8Array.from({ length: 16 }, (_, index) => index + 64);
    const plaintext = text('Bitwarden fused AES-CBC/HMAC test vector');
    const ciphertext = new Int8Array(48);
    const mac = new Int8Array(32);
    const encrypted = crypto.encrypt(encryptionKey, macKey, iv, plaintext, ciphertext, mac);
    check(encrypted[0] === 0 && encrypted[1] === 48, 'Encryption status');
    check(hex(ciphertext) === 'b3ae5dcb9dd806f8266f89d2e9e3489d37964364df9a2b1767d16f3fda8f82ae088a3c1a342b9b5b72417ed002bc0248', 'AES vector');
    check(hex(mac) === '6f9cc3bd0c5cd61850923fe87d0edb133fc1f84e7f7a513658b87dd2d35359c8', 'HMAC vector');
    const decrypted = new Int8Array(ciphertext.length);
    const result = crypto.decrypt(encryptionKey, macKey, iv, ciphertext, mac, decrypted);
    check(result[0] === 0 && hex(decrypted.slice(0, result[1])) === hex(plaintext), 'Decryption');
    mac[0] ^= 1;
    decrypted.fill(42);
    const rejected = crypto.decrypt(encryptionKey, macKey, iv, ciphertext, mac, decrypted);
    check(rejected[0] === 6 && decrypted.every(byte => byte === 0), 'MAC rejection/output clearing');
    return { passed: true, abi: crypto.abiVersion(), startupMs, pbkdf2Ms, argon2MiB: 64, argon2Ms };
}

if (typeof document !== 'undefined') {
    const worker = new Worker(new URL(import.meta.url), { type: 'module' });
    worker.onmessage = ({ data }) => {
        globalThis.cryptoWasmResult = data;
        document.body.textContent = JSON.stringify(data, null, 2);
        worker.terminate();
    };
    worker.onerror = event => {
        globalThis.cryptoWasmResult = { passed: false, error: event.message };
        document.body.textContent = JSON.stringify(globalThis.cryptoWasmResult);
        worker.terminate();
    };
} else {
    try {
        const result = await run();
        if (typeof postMessage === 'function') postMessage(result);
        else console.log(JSON.stringify(result));
    } catch (error) {
        if (typeof postMessage === 'function') postMessage({ passed: false, error: error.message });
        else throw error;
    }
}
