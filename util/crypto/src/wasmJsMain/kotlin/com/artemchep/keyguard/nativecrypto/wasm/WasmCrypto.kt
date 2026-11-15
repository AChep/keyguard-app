@file:JsModule("keyguard-crypto-wasm")
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.artemchep.keyguard.nativecrypto.wasm

import kotlin.js.JsAny
import kotlin.js.Promise

@JsName("initialize")
internal external fun wasmInitialize(): Promise<JsAny?>

@JsName("abiVersion")
internal external fun wasmAbiVersion(): Int

@JsName("capabilities")
internal external fun wasmCapabilities(): JsCryptoArray

@JsName("randomInt")
internal external fun wasmRandomInt(bound: Int): JsCryptoArray

@JsName("call")
internal external fun wasmCall(request: JsCryptoArray): JsCryptoArray

@JsName("streamOpen")
internal external fun wasmStreamOpen(request: JsCryptoArray): JsCryptoArray

@JsName("streamUpdate")
internal external fun wasmStreamUpdate(low: Int, high: Int, input: JsCryptoArray): JsCryptoArray

@JsName("streamFinish")
internal external fun wasmStreamFinish(low: Int, high: Int): JsCryptoArray

@JsName("streamClose")
internal external fun wasmStreamClose(low: Int, high: Int): JsCryptoArray

@JsName("encrypt")
internal external fun wasmEncrypt(
    encryptionKey: JsCryptoArray,
    macKey: JsCryptoArray,
    iv: JsCryptoArray,
    plaintext: JsCryptoArray,
    ciphertextOutput: JsCryptoArray,
    macOutput: JsCryptoArray,
): JsCryptoArray

@JsName("decrypt")
internal external fun wasmDecrypt(
    encryptionKey: JsCryptoArray,
    macKey: JsCryptoArray,
    iv: JsCryptoArray,
    ciphertext: JsCryptoArray,
    expectedMac: JsCryptoArray,
    plaintextOutput: JsCryptoArray,
): JsCryptoArray
