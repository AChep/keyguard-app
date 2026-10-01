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
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask

/** App Group container shared by the main app and the AutoFill credential-provider extension. */
private const val DEFAULT_APP_GROUP_IDENTIFIER = "group.com.artemchep.keyguard"

/**
 * Reads the per-bundle `KeyguardAppGroupIdentifier` Info.plist key, set from `KEYGUARD_APP_GROUP_ID`
 * in `xcode/Signing.xcconfig` (overridable in `xcode/Signing.local.xcconfig`).
 */
private fun appGroupIdentifier(): String {
    val value = NSBundle.mainBundle
        .objectForInfoDictionaryKey("KeyguardAppGroupIdentifier") as? String
    // An unsubstituted "$(...)" means the bundle was built without the setting;
    // treat it the same as a missing key rather than asking for a bogus group.
    return value?.takeIf { it.isNotBlank() && !it.startsWith("$") }
        ?: DEFAULT_APP_GROUP_IDENTIFIER
}

/**
 * The Keyguard vault data directory. Prefers the **App Group container** so the
 * main app and AutoFill extension share one vault; falls back to Application
 * Support when the App-Group entitlement is not granted (e.g. an unsigned / ad-hoc
 * build with no provisioning), so the app keeps working without a Developer Team.
 */
fun appleKeyguardDataDirectory(): LocalPath =
    appleKeyguardAtomicDataDirectory().path

/** Existing Apple-managed container plus Keyguard's strict descendant. */
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

/** The App Group container path, or null when the entitlement isn't present or the container isn't writable. */
fun appleAppGroupContainerPath(): String? = writableAppGroupContainerPath

private val writableAppGroupContainerPath: String? by lazy {
    resolveWritableAppGroupContainerPath()
}

private fun appGroupContainerPath(): String? =
    NSFileManager.defaultManager
        .containerURLForSecurityApplicationGroupIdentifier(appGroupIdentifier())
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
