package com.artemchep.keyguard.util.agentipc

/**
 * Where [AgentIpcListener.bind] creates its endpoint.
 *
 * Prefixes have 0–32 bytes and exact names 1–64 bytes, both of `[A-Za-z0-9._-]`,
 * never `.` or `..`. An exact name is for tests; production endpoints get a random
 * name. A location of the other platform fails with
 * [AgentIpcFailureKind.INVALID_ARGUMENT].
 */
sealed interface AgentIpcLocation {
    /**
     * `<parent>/<prefix><12 hex>/i` in a new 0700 directory; [exactName] replaces
     * `<prefix><12 hex>`. [parent] is an existing absolute directory, which may be a
     * symbolic link, and the socket path fits `sun_path`: at most 103 bytes on macOS
     * and 107 on Linux. Linux and macOS.
     */
    class UnixDirectory(
        val parent: String,
        val prefix: String = "",
        val exactName: String? = null,
    ) : AgentIpcLocation

    /** `\\.\pipe\<prefix><32 hex>`, or `\\.\pipe\<exactName>`. Windows. */
    class WindowsPipe(
        val prefix: String,
        val exactName: String? = null,
    ) : AgentIpcLocation
}
