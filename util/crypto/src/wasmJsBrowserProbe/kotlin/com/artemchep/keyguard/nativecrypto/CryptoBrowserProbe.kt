@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("MagicNumber", "UnusedParameter") // Test vectors and parameters consumed by js() bodies.

package com.artemchep.keyguard.nativecrypto

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.time.TimeSource

private const val MICROS_PER_MILLISECOND: Double = 1_000.0

/** Browser worker executable for the public Kotlin crypto API. */
fun main() {
    val operation: suspend () -> Unit = { runProbe() }
    operation.startCoroutine(object : Continuation<Unit> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<Unit>) {
            result.exceptionOrNull()?.let { reportFailure(it.message ?: "Crypto probe failed") }
        }
    })
}

private suspend fun runProbe() {
    val startup = TimeSource.Monotonic.markNow()
    initializeNativeCryptoWasm()
    val startupMs = startup.elapsedNow().inWholeMicroseconds / MICROS_PER_MILLISECOND
    val crypto = NativeCrypto.primitives
    check(crypto.sha256("abc".encodeToByteArray()).contentEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".hexToByteArray(),
    ))
    check(crypto.pbkdf2Sha256("password".encodeToByteArray(), "salt".encodeToByteArray(), 1, 32).contentEquals(
        "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b".hexToByteArray(),
    ))
    val pbkdf2Start = TimeSource.Monotonic.markNow()
    check(crypto.pbkdf2Sha256("password".encodeToByteArray(), "salt".encodeToByteArray(), 600_000, 32).size == 32)
    val pbkdf2Ms = pbkdf2Start.elapsedNow().inWholeMicroseconds / MICROS_PER_MILLISECOND
    val argon2Start = TimeSource.Monotonic.markNow()
    check(crypto.argon2(
        mode = NativeArgon2Mode.ARGON2_ID,
        seed = "password".encodeToByteArray(),
        salt = "somesalt".encodeToByteArray(),
        iterations = 1,
        memoryKb = 65_536,
        parallelism = 1,
        length = 24,
    ).size == 24)
    val argon2Ms = argon2Start.elapsedNow().inWholeMicroseconds / MICROS_PER_MILLISECOND

    val encryptionKey = ByteArray(32) { it.toByte() }
    val macKey = ByteArray(32) { (it + 32).toByte() }
    val iv = ByteArray(16) { (it + 64).toByte() }
    val plaintext = "Bitwarden fused AES-CBC/HMAC test vector".encodeToByteArray()
    val encrypted = crypto.aesCbcPkcs7HmacSha256Encrypt(encryptionKey, macKey, iv, plaintext)
    try {
        check(encrypted.ciphertext.contentEquals(
            ("b3ae5dcb9dd806f8266f89d2e9e3489d37964364df9a2b1767d16f3fda8f82ae" +
                "088a3c1a342b9b5b72417ed002bc0248").hexToByteArray(),
        ))
        val decrypted = crypto.aesCbcPkcs7HmacSha256Decrypt(
            encryptionKey, macKey, iv, encrypted.ciphertext, encrypted.mac,
        )
        try {
            check(decrypted.contentEquals(plaintext))
        } finally {
            decrypted.fill(0)
        }
    } finally {
        encryptionKey.fill(0)
        macKey.fill(0)
        plaintext.fill(0)
        encrypted.ciphertext.fill(0)
        encrypted.mac.fill(0)
    }
    checkBoundedOpenPgp()
    reportSuccess(startupMs, pbkdf2Ms, argon2Ms)
}

private fun checkBoundedOpenPgp() {
    check(NativeCryptoCapability.OPENPGP_MESSAGE_STREAMING !in NativeCrypto.capabilities)
    check(NativeCryptoCapability.OPENPGP_DETACHED_VERIFY_STREAMING !in NativeCrypto.capabilities)
    check(NativeCryptoCapability.OPENPGP_STREAM_DRAIN !in NativeCrypto.capabilities)
    val material = NativeCrypto.openPgp.generateKey(
        kind = NativeOpenPgpKeyKind.LEGACY_ED25519_X25519,
        version = NativeOpenPgpKeyVersion.V4,
        userId = "Browser crypto probe <probe@test.invalid>",
        creationTimeEpochSeconds = 1_700_000_000L,
    )
    try {
        val content = "Browser authenticated OpenPGP".encodeToByteArray()
        val encrypted = NativeCrypto.openPgp.encrypt(
            content = content,
            publicKeys = listOf(material.publicKeyArmored),
            candidateRevocationKeys = emptyList(),
            signingPrivateKey = material.privateKeyArmored,
            fileName = "probe.txt",
            armored = false,
        )
        val decrypted = NativeCrypto.openPgp.decrypt(
            content = encrypted.data,
            privateKeys = listOf(material.privateKeyArmored),
            verificationPublicKeys = listOf(material.publicKeyArmored),
        )
        try {
            check(decrypted.data.contentEquals(content))
            check(decrypted.verification?.status == NativeOpenPgpVerificationStatus.VALID)
            check(decrypted.encrypted)
            check(decrypted.metadata?.fileName?.contentEquals("probe.txt".encodeToByteArray()) == true)
        } finally {
            decrypted.data.fill(0)
        }
        val failure = runCatching { NativeCrypto.openPgp.openDecryption(emptyList()) }.exceptionOrNull()
        check(failure is NativeCryptoException && failure.code == NativeCryptoErrorCode.UNSUPPORTED_OPERATION)
    } finally {
        material.privateKeyArmored.fill(0)
        material.publicKeyArmored.fill(0)
    }
}

private fun reportSuccess(startupMs: Double, pbkdf2Ms: Double, argon2Ms: Double): Unit = js("""{
    globalThis.postMessage({passed: true, target: "kotlin-wasm-gc", openPgp: true, startupMs, pbkdf2Ms, argon2MiB: 64, argon2Ms});
}""")

private fun reportFailure(message: String): Unit = js("{ globalThis.postMessage({passed: false, error: message}); }")
