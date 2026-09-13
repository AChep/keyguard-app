package com.artemchep.keyguard.feature.gpgagent.tools

import com.artemchep.keyguard.feature.gpgagent.tools.result.GpgToolsResultRoute

/** Owns private output staging for platforms that export completed files. */
interface GpgToolsOutputCoordinator {
    /** Keeps operation inputs alive, including verification without an output. */
    suspend fun run(block: suspend () -> Unit) {
        block()
    }

    /**
     * Supplies an app-owned writable URI and publishes its artifact only after
     * [block] succeeds. Implementations must remove partial output on failure or
     * cancellation and retain successful output until its owner releases it.
     */
    suspend fun write(
        fileName: String,
        incognito: Boolean,
        block: suspend (uri: String) -> Unit,
    ): GpgToolsResultRoute.Args.FileOutput
}
