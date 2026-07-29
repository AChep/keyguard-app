package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.util.io.InternalKeyguardIoApi

internal typealias PrivateTemporaryStorage =
    com.artemchep.keyguard.util.io.scratch.PrivateTemporaryStorage

/**
 * Returns the directory that hosts private scratch storage on this platform;
 * the orphan sweeper reclaims stale artifacts from it at startup.
 */
internal expect fun privateTemporaryStorageDirectory(): LocalPath

@OptIn(InternalKeyguardIoApi::class)
internal fun createPrivateTemporaryStorage(): PrivateTemporaryStorage =
    com.artemchep.keyguard.util.io.scratch.createPrivateTemporaryStorage(
        directory = privateTemporaryStorageDirectory(),
    )
