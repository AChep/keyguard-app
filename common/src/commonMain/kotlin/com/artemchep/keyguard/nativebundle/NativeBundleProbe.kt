package com.artemchep.keyguard.nativebundle

import com.artemchep.keyguard.util.age.AgePassword
import com.artemchep.keyguard.util.age.AgeZipWriter
import com.artemchep.keyguard.util.age.AgeZipReader
import kotlinx.io.Buffer
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeCryptoNoise
import com.artemchep.keyguard.nativecrypto.NativeNoiseHandshake
import com.artemchep.keyguard.util.io.LocalPath
import com.artemchep.keyguard.util.io.atomic.AtomicFileDestination
import com.artemchep.keyguard.util.io.atomic.AtomicFilePermissions
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationPolicy
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath
import com.artemchep.keyguard.util.io.atomic.AtomicWriteOptions
import com.artemchep.keyguard.util.io.atomic.ExistingParentLinkPolicy
import com.artemchep.keyguard.util.io.atomic.ParentDirectoryPolicy
import com.artemchep.keyguard.util.io.atomic.SyncLevel
import com.artemchep.keyguard.util.io.atomic.SynchronizationPolicy
import com.artemchep.keyguard.util.io.atomic.writeFileAtomically
import com.artemchep.keyguard.util.io.delete
import com.artemchep.keyguard.util.io.readBytes
import com.artemchep.keyguard.util.zxcvbn.Zxcvbn
import kotlinx.io.write

private const val MAX_ZXCVBN_SCORE = 4

/** Checks that each native library loads and does real work, in a caller-owned temporary root. */
object NativeBundleProbe {
    fun run(root: LocalPath) {
        nativeProbe("crypto") {
            verifyCrypto()
            verifyNoise()
        }
        nativeProbe("io") { verifyAtomicIo(root) }
        nativeProbe("age") {
            val ciphertext = Buffer()
            AgeZipWriter(ciphertext, AgePassword("native bundle probe")).use { it.finish() }
            AgeZipReader(ciphertext, AgePassword("native bundle probe")).use { check(it.nextEntry() == null) }
        }
        nativeProbe("zxcvbn") {
            val estimate = Zxcvbn.estimate("native bundle probe")
            check(estimate.guesses > 0 && estimate.score in 0..MAX_ZXCVBN_SCORE) {
                "Native zxcvbn estimate is invalid"
            }
        }
    }

    private fun verifyCrypto() {
        NativeCrypto.ensureReady()
        val expectedDigest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
        check(NativeCrypto.primitives.sha256("abc".encodeToByteArray()).contentEquals(expectedDigest)) {
            "Native crypto SHA-256 result mismatch"
        }
    }

    /** Runs an in-process XXpsk3 handshake, then one transport message. */
    private fun verifyNoise() {
        val noise = NativeCrypto.noise
        val psk = ByteArray(NativeCryptoNoise.PSK_SIZE) { it.toByte() }
        val keys = List(2) { noise.generateKeyPair() }
        try {
            noise.xxPsk3(initiator = true, localPrivateKey = keys[0].privateKey, psk = psk).use { initiator ->
                noise.xxPsk3(initiator = false, localPrivateKey = keys[1].privateKey, psk = psk).use { responder ->
                    verifyNoiseSession(initiator, responder)
                }
            }
        } finally {
            keys.forEach { it.privateKey.fill(0) }
        }
    }

    private fun verifyNoiseSession(initiator: NativeNoiseHandshake, responder: NativeNoiseHandshake) {
        responder.readMessage(initiator.writeMessage())
        initiator.readMessage(responder.writeMessage())
        responder.readMessage(initiator.writeMessage())
        check(initiator.handshakeHash().contentEquals(responder.handshakeHash())) {
            "Native noise handshake hash mismatch"
        }
        initiator.intoTransport().use { sender ->
            responder.intoTransport().use { receiver ->
                val payload = "native bundle probe".encodeToByteArray()
                check(receiver.readMessage(sender.writeMessage(payload)).contentEquals(payload)) {
                    "Native noise round trip mismatch"
                }
            }
        }
    }

    private fun verifyAtomicIo(root: LocalPath) {
        val destination = AtomicFileDestination(root, AtomicRelativePath.parse("native-bundle.bin"))
        val payload = "native bundle probe".encodeToByteArray()
        try {
            writeFileAtomically(
                destination = destination,
                options = AtomicWriteOptions(
                    publication = AtomicPublicationPolicy.Create(AtomicFilePermissions.OwnerOnly),
                    parentDirectories = ParentDirectoryPolicy.RequireExisting,
                    existingParentLinks = ExistingParentLinkPolicy.Reject,
                    synchronization = SynchronizationPolicy.Required(SyncLevel.FileSynchronized),
                ),
            ) { sink -> sink.write(payload) }
            check(destination.path.readBytes().contentEquals(payload)) { "Native IO round trip mismatch" }
        } finally {
            destination.path.delete()
        }
    }
}

/** Adds a stable component name to diagnostics from platform-specific packaging probes. */
// Native loading can throw errors as well as exceptions; preserve the cause when adding context.
@Suppress("TooGenericExceptionCaught")
inline fun <T> nativeProbe(component: String, block: () -> T): T = try {
    block()
} catch (error: Throwable) {
    throw IllegalStateException("Native $component probe failed: ${error.message}", error)
}
