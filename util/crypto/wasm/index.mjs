import createKeyguardCrypto from './keyguard_crypto.mjs';

let runtime;
let loading;

/** Load once per JS realm before calling the synchronous Kotlin crypto API. */
export async function initialize() {
    if (!loading) {
        loading = createKeyguardCrypto().then(module => {
            runtime = module;
        }).catch(error => {
            loading = undefined;
            throw error;
        });
    }
    await loading;
}

function ready() {
    if (!runtime) throw new Error('Initialize Keyguard crypto Wasm before use');
    return runtime;
}

// Always reacquire HEAP views after calls/allocations: memory growth detaches them.
function arena(block) {
    const module = ready();
    const allocations = [];
    const allocate = size => {
        const pointer = module._malloc(Math.max(size, 1));
        if (!pointer) throw new Error('Keyguard crypto Wasm allocation failed');
        allocations.push([pointer, size]);
        module.HEAPU8.fill(0, pointer, pointer + size);
        return pointer;
    };
    const input = bytes => {
        const pointer = allocate(bytes.length);
        module.HEAPU8.set(bytes, pointer);
        return pointer;
    };
    try {
        return block(module, allocate, input);
    } finally {
        for (const [pointer, size] of allocations.reverse()) {
            module.HEAPU8.fill(0, pointer, pointer + size);
            module._free(pointer);
        }
    }
}

function response(invoke) {
    return arena((module, allocate, input) => {
        // wasm32: KeyguardCryptoBuffer is three 32-bit words: ptr, len, capacity.
        const output = allocate(12);
        try {
            const status = invoke(module, input, output);
            if (status !== 0) throw new Error(`Keyguard crypto transport failed (${status})`);
            const pointer = module.HEAPU32[output >>> 2];
            const length = module.HEAPU32[(output >>> 2) + 1];
            if (length > 16 * 1024 * 1024 || pointer + length > module.HEAPU8.length) {
                throw new Error('Invalid Keyguard crypto response');
            }
            return new Int8Array(module.HEAPU8.buffer, pointer, length).slice();
        } finally {
            module._keyguard_crypto_buffer_free(output);
        }
    });
}

// Passing two words avoids truncating a Kotlin/JS Long through a JS Number.
function handle(low, high) {
    return (BigInt(high >>> 0) << 32n) | BigInt(low >>> 0);
}

export function abiVersion() {
    return ready()._keyguard_crypto_abi_version();
}

export function capabilities() {
    const value = ready()._keyguard_crypto_capabilities();
    return Int32Array.of(Number(value & 0xffffffffn), Number(value >> 32n));
}

export function randomInt(bound) {
    return arena((module, allocate) => {
        const output = allocate(4);
        const status = module._keyguard_crypto_random_int(bound, output);
        return Int32Array.of(status, module.HEAP32[output >>> 2]);
    });
}

export function call(request) {
    return response((module, input, output) =>
        module._keyguard_crypto_call(input(request), request.length, output));
}

export function streamOpen(request) {
    return response((module, input, output) =>
        module._keyguard_crypto_stream_open(input(request), request.length, output));
}

export function streamUpdate(low, high, bytes) {
    return response((module, input, output) =>
        module._keyguard_crypto_stream_update(handle(low, high), input(bytes), bytes.length, output));
}

export function streamFinish(low, high) {
    return response((module, input, output) =>
        module._keyguard_crypto_stream_finish(handle(low, high), output));
}

export function streamClose(low, high) {
    return response((module, input, output) =>
        module._keyguard_crypto_stream_close(handle(low, high), output));
}

export function encrypt(encryptionKey, macKey, iv, plaintext, ciphertext, mac) {
    return arena((module, allocate, input) => {
        const keyPointer = input(encryptionKey);
        const macKeyPointer = input(macKey);
        const ivPointer = input(iv);
        const dataPointer = input(plaintext);
        const ciphertextPointer = allocate(ciphertext.length);
        const macPointer = allocate(mac.length);
        const sizePointer = allocate(4);
        const status = module._keyguard_crypto_aes_cbc_pkcs7_hmac_sha256_encrypt(
            keyPointer, encryptionKey.length, macKeyPointer, macKey.length,
            ivPointer, iv.length, dataPointer, plaintext.length,
            ciphertextPointer, ciphertext.length, macPointer, mac.length, sizePointer,
        );
        ciphertext.set(module.HEAPU8.subarray(ciphertextPointer, ciphertextPointer + ciphertext.length));
        mac.set(module.HEAPU8.subarray(macPointer, macPointer + mac.length));
        return Int32Array.of(status, module.HEAPU32[sizePointer >>> 2]);
    });
}

export function decrypt(encryptionKey, macKey, iv, ciphertext, expectedMac, plaintext) {
    return arena((module, allocate, input) => {
        const keyPointer = input(encryptionKey);
        const macKeyPointer = input(macKey);
        const ivPointer = input(iv);
        const dataPointer = input(ciphertext);
        const macPointer = input(expectedMac);
        const plaintextPointer = allocate(plaintext.length);
        const sizePointer = allocate(4);
        const status = module._keyguard_crypto_aes_cbc_pkcs7_hmac_sha256_decrypt(
            keyPointer, encryptionKey.length, macKeyPointer, macKey.length,
            ivPointer, iv.length, dataPointer, ciphertext.length,
            macPointer, expectedMac.length, plaintextPointer, plaintext.length, sizePointer,
        );
        plaintext.set(module.HEAPU8.subarray(plaintextPointer, plaintextPointer + plaintext.length));
        return Int32Array.of(status, module.HEAPU32[sizePointer >>> 2]);
    });
}
