@file:JsModule("keyguard-crypto-wasm")

package com.artemchep.keyguard.nativecrypto.wasm

import kotlin.js.Promise

@JsName("initialize")
internal external fun wasmInitialize(): Promise<Unit>

@JsName("abiVersion")
internal external fun wasmAbiVersion(): Int

@JsName("capabilities")
internal external fun wasmCapabilities(): IntArray

@JsName("randomInt")
internal external fun wasmRandomInt(bound: Int): IntArray

@JsName("call")
internal external fun wasmCall(request: ByteArray): ByteArray

@JsName("streamOpen")
internal external fun wasmStreamOpen(request: ByteArray): ByteArray

@JsName("streamUpdate")
internal external fun wasmStreamUpdate(low: Int, high: Int, input: ByteArray): ByteArray

@JsName("streamFinish")
internal external fun wasmStreamFinish(low: Int, high: Int): ByteArray

@JsName("streamClose")
internal external fun wasmStreamClose(low: Int, high: Int): ByteArray

@JsName("encrypt")
internal external fun wasmEncrypt(
    encryptionKey: ByteArray,
    macKey: ByteArray,
    iv: ByteArray,
    plaintext: ByteArray,
    ciphertextOutput: ByteArray,
    macOutput: ByteArray,
): IntArray

@JsName("decrypt")
internal external fun wasmDecrypt(
    encryptionKey: ByteArray,
    macKey: ByteArray,
    iv: ByteArray,
    ciphertext: ByteArray,
    expectedMac: ByteArray,
    plaintextOutput: ByteArray,
): IntArray
