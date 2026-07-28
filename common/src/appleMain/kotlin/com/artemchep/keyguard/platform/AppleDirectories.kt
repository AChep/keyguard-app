package com.artemchep.keyguard.platform

import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSUUID

/**
 * App Group container shared by the main app, the SSH agent socket, and the
 * AutoFill credential-provider extension. Resolving the vault data dir from here
 * is the single high-leverage change that lets all three see the same storage
 * (see IMPL.md G4).
 */
private const val APP_GROUP_IDENTIFIER = "group.com.artemchep.keyguard"

/**
 * The Keyguard vault data directory. Prefers the **App Group container** so the
 * SSH agent + AutoFill extension share one vault; falls back to Application
 * Support when the App-Group entitlement is not granted (e.g. an unsigned / ad-hoc
 * build with no provisioning), so the app keeps working without a Developer Team.
 */
fun appleKeyguardDataDirectory(): LocalPath {
    val groupBase = writableAppGroupContainerPath
    if (groupBase != null) {
        return LocalPath(groupBase).resolve("Keyguard")
    }
    val base = NSSearchPathForDirectoriesInDomains(
        directory = NSApplicationSupportDirectory,
        domainMask = NSUserDomainMask,
        expandTilde = true,
    ).firstOrNull() as? String ?: error("Application Support directory is not available.")
    return LocalPath(base).resolve("Keyguard")
}

/**
 * The App Group container path, or null when the entitlement isn't present. Also
 * the parent of the SSH agent socket (`<container>/ssh-agent.sock`).
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
    val keyguardDir = baseDir.resolve("Keyguard")
    val writable = listOf(
        baseDir,
        keyguardDir,
        keyguardDir.resolve("keyvalue"),
        keyguardDir.resolve("vault"),
        keyguardDir.resolve("exposed"),
        keyguardDir.resolve("cache"),
        keyguardDir.resolve("pending_uploads"),
    ).all(::canWriteDirectory)
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
