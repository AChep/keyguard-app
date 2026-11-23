package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.util.instance.InstanceOwnership

/**
 * Ownership outlives every scene and stays held until the process exits. No app
 * service stops before termination, so the OS releases the lock at process exit.
 */
class MacosInstance internal constructor(
    @Suppress("unused")
    private val ownership: InstanceOwnership,
)
