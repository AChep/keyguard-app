package com.artemchep.keyguard.platform

import com.artemchep.keyguard.crypto.privateTemporaryStorageDirectory
import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryDestination

/** App-owned locations evaluated on access, without probing storage during provider construction. */
interface AppleStorageDirectories {
    val data: AtomicDirectoryDestination
    val cache: LocalPath
    val temporary: LocalPath
}

/**
 * Production locations: data in the preflighted App Group, cache and
 * temporary files in their platform directories.
 */
class AppleStorageDirectoriesImpl : AppleStorageDirectories {
    override val data: AtomicDirectoryDestination
        get() = appleKeyguardAtomicDataDirectory()

    override val cache: LocalPath
        get() = appleKeyguardCacheDirectory()

    override val temporary: LocalPath
        get() = privateTemporaryStorageDirectory()
}

/** iOS keeps the cache in the system Caches directory, macOS keeps it in the App Group. */
internal expect fun appleKeyguardCacheDirectory(): LocalPath
