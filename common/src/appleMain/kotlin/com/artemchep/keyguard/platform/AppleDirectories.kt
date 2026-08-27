package com.artemchep.keyguard.platform

import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryDestination
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath
import com.artemchep.keyguard.util.io.resolve
import com.artemchep.keyguard.util.io.toKotlinxIoPath
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask

/**
 * App Group container shared by the main app and the AutoFill
 * credential-provider extension. Resolving the vault data dir from here
 * is the single high-leverage change that lets both see the same storage
 * (see IMPL.md G4).
 */
private const val APP_GROUP_IDENTIFIER = "group.com.artemchep.keyguard"

/**
 * The Keyguard vault data directory. Prefers the **App Group container** so the
 * main app and AutoFill extension share one vault; falls back to Application
 * Support when the App-Group entitlement is not granted (e.g. an unsigned / ad-hoc
 * build with no provisioning), so the app keeps working without a Developer Team.
 */
fun appleKeyguardDataDirectory(): LocalPath =
    appleKeyguardAtomicDataDirectory().path

/**
 * Existing Apple-managed container plus Keyguard's strict descendant.
 */
fun appleKeyguardAtomicDataDirectory(): AtomicDirectoryDestination {
    val root = writableAppGroupContainerPath ?: run {
        val base = NSSearchPathForDirectoriesInDomains(
            directory = NSApplicationSupportDirectory,
            domainMask = NSUserDomainMask,
            expandTilde = true,
        ).firstOrNull() as? String ?: error("Application Support directory is not available.")
        base
    }
    return AtomicDirectoryDestination(
        root = LocalPath(root),
        relativePath = AtomicRelativePath.fromComponents(
            AtomicPathComponent.parse("Keyguard"),
        ),
    )
}

/**
 * The App Group container path, or null when the entitlement isn't present.
 */
fun appleAppGroupContainerPath(): String? = writableAppGroupContainerPath

private val writableAppGroupContainerPath: String? by lazy {
    resolveWritableAppGroupContainerPath()
}

private fun appGroupContainerPath(): String? =
    NSFileManager.defaultManager
        .containerURLForSecurityApplicationGroupIdentifier(APP_GROUP_IDENTIFIER)
        ?.path

private fun resolveWritableAppGroupContainerPath(): String? {
    val path = appGroupContainerPath() ?: return null
    val baseDir = LocalPath(path)
    // Probe only the existing platform container. Atomic writers must own
    // creation and synchronization of every Keyguard descendant.
    val writable = canWriteDirectory(baseDir)
    return path.takeIf { writable }
}

private fun canWriteDirectory(directory: LocalPath): Boolean {
    val probe = directory.resolve(".write-probe-${NSUUID().UUIDString}")
    val canWrite = runCatching {
        SystemFileSystem.createDirectories(directory.toKotlinxIoPath())
        SystemFileSystem.sink(probe.toKotlinxIoPath())
            .buffered()
            .use { sink ->
                sink.writeString("ok")
            }
        true
    }.getOrDefault(false)
    runCatching {
        SystemFileSystem.delete(probe.toKotlinxIoPath())
    }
    return canWrite
}
