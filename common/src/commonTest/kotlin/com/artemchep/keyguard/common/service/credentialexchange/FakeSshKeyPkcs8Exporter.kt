package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.service.crypto.SshKeyPkcs8Export
import com.artemchep.keyguard.common.service.crypto.SshKeyPkcs8Exporter

/**
 * A test double for [SshKeyPkcs8Exporter] that returns a fixed DER (or `null` to
 * simulate an unconvertible key), so mapper tests never touch the native crypto
 * library. A fresh copy is handed out each call because the mapper zeroes the
 * returned array.
 *
 * [ders] scripts the result per call, falling back to [der] once exhausted — a
 * vault with two SSH keys can then have one convert and one fail. [pems] and
 * [publicKeys] record the pair the mapper passed to the native seam. [error]
 * makes the seam *raise* instead of returning, exercising the guard in
 * `mapSshKey`.
 *
 * This is a resilience fixture: reaching it with `error` set must produce a
 * counted skip, because the export has to survive a broken backend. Reaching
 * [ThrowingSshKeyPkcs8Exporter] must instead fail the test, so the two are not
 * interchangeable.
 */
class FakeSshKeyPkcs8Exporter(
    private val der: ByteArray? = null,
    private val ders: List<ByteArray?> = emptyList(),
    private val error: Throwable? = null,
    private val type: KeyPair.Type = KeyPair.Type.ED25519,
) : SshKeyPkcs8Exporter {
    val pems = mutableListOf<String>()
    val publicKeys = mutableListOf<String>()

    val lastPem: String? get() = pems.lastOrNull()
    val lastPublicKey: String? get() = publicKeys.lastOrNull()

    val callCount: Int get() = pems.size

    override fun exportPkcs8(
        privateKeyPem: String,
        publicKeyOpenSsh: String,
    ): SshKeyPkcs8Export? {
        pems += privateKeyPem
        publicKeys += publicKeyOpenSsh
        error?.let { throw it }
        val index = pems.size - 1
        val result = if (ders.isNotEmpty()) ders.getOrNull(index) else der
        return result?.copyOf()?.let { bytes ->
            SshKeyPkcs8Export(
                type = type,
                der = bytes,
            )
        }
    }
}

/**
 * Hands out the *same* array every call instead of a copy, so a test can observe
 * that the mapper zeroed it. [FakeSshKeyPkcs8Exporter] deliberately copies, which
 * leaves the zeroing invisible.
 */
class RecordingSshKeyPkcs8Exporter(
    private val der: ByteArray,
    private val type: KeyPair.Type = KeyPair.Type.ED25519,
) : SshKeyPkcs8Exporter {
    /**
     * The array handed to the mapper. After the call it should be all zeros —
     * the mapper owns the buffer and wipes it once encoded.
     */
    val handedOut: ByteArray get() = der

    override fun exportPkcs8(
        privateKeyPem: String,
        publicKeyOpenSsh: String,
    ): SshKeyPkcs8Export = SshKeyPkcs8Export(
        type = type,
        der = der,
    )
}

/**
 * A must-not-be-reached tripwire, unlike `FakeSshKeyPkcs8Exporter(error = ...)`,
 * which asserts that a throw *is* survivable. `mapSshKey` absorbs the
 * `error(message)` below into one counted SSH-key skip, so a test using this
 * double must assert the skip tally rather than merely that nothing threw.
 */
class ThrowingSshKeyPkcs8Exporter(
    private val message: String = "the ssh key exporter must not be reached",
) : SshKeyPkcs8Exporter {
    override fun exportPkcs8(
        privateKeyPem: String,
        publicKeyOpenSsh: String,
    ): SshKeyPkcs8Export? = error(message)
}
