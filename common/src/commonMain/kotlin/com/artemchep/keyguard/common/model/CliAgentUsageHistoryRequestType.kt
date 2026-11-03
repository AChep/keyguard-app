package com.artemchep.keyguard.common.model

enum class CliAgentUsageHistoryRequestType(
    val code: Long,
) {
    RUN(code = 0L),
    MCP_AUTHENTICATE(code = 1L),
    MCP_LIST_ENVIRONMENTS(code = 2L),
    MCP_CREATE_ENVIRONMENT(code = 3L),
    MCP_RENAME_ENVIRONMENT(code = 4L),
    MCP_LIST_VARIABLES(code = 5L),
    MCP_APPEND_VARIABLES(code = 6L),
    MCP_CREATE_LOCAL_ENV_FILE(code = 7L),
    MCP_LIST_LOCAL_ENV_FILES(code = 8L),
    READ_LOCAL_ENV_FILE(code = 9L),

    /**
     * An unrecognized request type: a command or a tool that this app
     * version does not know, or a code written by a newer app version.
     */
    UNKNOWN(code = -1L),
    ;

    companion object {
        fun of(code: Long): CliAgentUsageHistoryRequestType =
            entries.firstOrNull { it.code == code }
            // Fall back to UNKNOWN instead of crashing so that a database
            // written by a newer app version stays readable.
                ?: UNKNOWN
    }
}
