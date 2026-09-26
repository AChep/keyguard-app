package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryPermissions
import com.artemchep.keyguard.util.io.atomic.AtomicFileDestination
import com.artemchep.keyguard.util.io.atomic.AtomicWriteReceipt
import com.artemchep.keyguard.util.io.atomic.AtomicWriteResult
import com.artemchep.keyguard.util.io.atomic.ParentDirectoryPolicy
import com.artemchep.keyguard.util.io.atomic.SynchronizationPolicy
import com.artemchep.keyguard.util.io.atomic.writePrivatelyAtomically
import kotlinx.io.Source

/**
 * Decrypts [input] and atomically publishes owner-only plaintext at [output].
 *
 * [input] is borrowed. Resources opened for [output] are owned by this helper,
 * and an existing destination remains untouched if decryption fails.
 */
fun FileEncryptionCodec.decryptToPath(
    input: Source,
    output: AtomicFileDestination,
    key: ByteArray,
    synchronization: SynchronizationPolicy,
    checkCancellation: () -> Unit = {},
): AtomicWriteReceipt =
    writePrivatelyAtomically(
        destination = output,
        parentDirectories = privateParentDirectoryPolicy,
        synchronization = synchronization,
        checkCancellation = checkCancellation,
    ) { sink ->
        decrypt(
            input = input,
            output = sink,
            key = key,
            checkCancellation = checkCancellation,
        )
    }.receipt

/**
 * Encrypts [input] and atomically publishes an owner-only frame at [output].
 *
 * [input] is borrowed. Resources opened for [output] are owned by this helper,
 * and an existing destination remains untouched if encryption fails.
 */
fun FileEncryptionCodec.encryptToPath(
    input: Source,
    output: AtomicFileDestination,
    key: ByteArray,
    synchronization: SynchronizationPolicy,
    checkCancellation: () -> Unit = {},
): AtomicWriteResult<FileEncryptionCodec.EncryptionResult> = writePrivatelyAtomically(
    destination = output,
    parentDirectories = privateParentDirectoryPolicy,
    synchronization = synchronization,
    checkCancellation = checkCancellation,
) { sink ->
    encrypt(
        input = input,
        output = sink,
        key = key,
        checkCancellation = checkCancellation,
    )
}

private val privateParentDirectoryPolicy = ParentDirectoryPolicy.CreateMissing(
    permissions = AtomicDirectoryPermissions.OwnerOnly,
)
