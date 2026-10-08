package com.artemchep.keyguard.platform

import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryDestination
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath
import com.artemchep.keyguard.util.io.resolve
import com.artemchep.keyguard.util.io.toKotlinxIoPath
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID

private val storage = AppleStorageResolver(
    groupIdentifier = { NSBundle.mainBundle.objectForInfoDictionaryKey("KeyguardAppGroupIdentifier") as? String },
    groupContainer = { NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(it)?.path },
    probe = ::probeDirectory,
)

/** Resolves one root per process. Failed attempts may be retried before starting the core. */
fun prepareAppleStorage(): String? = try {
    storage.resolve()
    null
} catch (error: AppleStorageException) {
    error.message
}

fun appleKeyguardDataDirectory(): LocalPath = appleKeyguardAtomicDataDirectory().path

/** Existing Apple-managed container plus Keyguard's strict descendant. */
fun appleKeyguardAtomicDataDirectory(): AtomicDirectoryDestination = AtomicDirectoryDestination(
    root = LocalPath(storage.resolve()),
    relativePath = AtomicRelativePath.fromComponents(AtomicPathComponent.parse("Keyguard")),
)

/** The same preflighted App Group root used by the app, extensions, and agents. */
fun appleAppGroupContainerPath(): String = storage.resolve()

internal class AppleStorageException(messageKey: String, cause: Throwable? = null) : Exception(messageKey, cause)

internal class AppleStorageResolver(
    private val groupIdentifier: () -> String?,
    private val groupContainer: (String) -> String?,
    private val probe: (String) -> Unit,
) {
    private val lock = SynchronizedObject()
    private var root: String? = null

    fun resolve(): String = synchronized(lock) {
        root ?: resolveRoot().also { root = it }
    }

    // Each failure maps to a distinct startup message.
    @Suppress("ThrowsCount")
    private fun resolveRoot(): String {
        val identifier = groupIdentifier()
            ?.takeIf { it.isNotBlank() && !it.startsWith("$") }
            ?: throw AppleStorageException("storage_missing_configuration")
        val path = groupContainer(identifier)
            ?: throw AppleStorageException("storage_unavailable")
        try {
            probe(path)
        } catch (error: IOException) {
            throw AppleStorageException("storage_unwritable", error)
        }
        return path
    }
}

private fun probeDirectory(path: String) {
    val probe = LocalPath(path).resolve(".write-probe-${NSUUID().UUIDString}").toKotlinxIoPath()
    try {
        // Never create an App Group root ourselves. Apple owns its location and lifecycle.
        SystemFileSystem.sink(probe).buffered().use { it.writeString("ok") }
    } finally {
        SystemFileSystem.delete(probe, mustExist = false)
    }
}
