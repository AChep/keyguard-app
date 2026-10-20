package com.artemchep.keyguard.common.service.cli

/**
 * The side of the CLI service that a platform runtime drives: it describes
 * the CLI of the build. Screens use [CliService] instead.
 */
interface CliRuntimeHost : CliService {
    /**
     * @param path The bundled CLI, `null` if this build has none.
     * @param capabilities What the platform lets the bundled CLI do; each
     *   command states what it requires.
     * @param socketPath The socket override that the CLI must use.
     */
    fun configureRuntime(
        path: String?,
        capabilities: Set<CliPlatformCapability>,
        socketPath: String? = null,
    )
}
