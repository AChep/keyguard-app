package com.artemchep.keyguard.common.model

/** The feature of the CLI agent that a request reached. */
enum class CliAgentUsageHistoryClient(
    val code: Long,
) {
    /** The `keyguard` command-line tool. */
    CLI(0L),

    /** An MCP client. */
    MCP(1L),

    /** A program that reads a local `.env` file. */
    ENV(2L),

    /**
     * An unrecognized client, e.g. a code written by a newer app version.
     */
    UNKNOWN(-1L),
    ;

    companion object {
        fun of(code: Long): CliAgentUsageHistoryClient =
            entries.firstOrNull { it.code == code }
            // Fall back to UNKNOWN instead of crashing so that a database
            // written by a newer app version stays readable.
                ?: UNKNOWN

        /** The client of the [name]; [UNKNOWN] if no client has it. */
        fun ofName(name: String): CliAgentUsageHistoryClient =
            entries.firstOrNull { it.name == name }
                ?: UNKNOWN
    }
}
