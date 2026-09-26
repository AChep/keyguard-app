package com.artemchep.keyguard.nativebundle

import com.artemchep.keyguard.nativecrypto.NativeCrypto
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

/** Checks native loading and one real operation per library in a caller-owned temporary root. */
object NativeBundleProbe {
    fun run(root: LocalPath) {
        nativeProbe("crypto") { verifyCrypto() }
        nativeProbe("io") { verifyAtomicIo(root) }
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
