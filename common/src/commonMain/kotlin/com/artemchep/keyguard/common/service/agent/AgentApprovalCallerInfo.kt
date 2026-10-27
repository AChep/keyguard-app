package com.artemchep.keyguard.common.service.agent

/** The verified caller of an approval request, as one primary and one secondary line. */
data class AgentApprovalCallerInfo(
    val primaryLabel: String?,
    val secondaryLabel: String?,
)

/**
 * Builds the display lines of a [caller]. Every value is sanitized and
 * bounded; [resolvedAppName] is an OS-resolved, presentation-only hint.
 */
fun buildAgentApprovalCallerInfo(
    caller: AgentCallerIdentity?,
    resolvedAppName: String? = null,
): AgentApprovalCallerInfo? {
    caller ?: return null

    val resolvedName = resolvedAppName
        .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH)
    val appName = caller.appName
        .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH)
    val appBundlePath = caller.appBundlePath
        .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH)
    val processName = caller.processName
        .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH)
    // Native collectors include an authenticated signing identifier or a
    // verified get prefix in appName. Keep that security label primary;
    // the OS-resolved friendly name and icon are presentation-only hints.
    val primaryLabel = appName ?: resolvedName ?: appBundlePath ?: processName
    val secondaryLabel = buildSet {
        resolvedName
            ?.takeIf { it != primaryLabel }
            ?.let(::add)
        // Always pair friendly labels with the package or bundle location when
        // one is available.
        appBundlePath
            ?.takeIf { it != primaryLabel }
            ?.let(::add)
        if (processName != null && primaryLabel != processName && appBundlePath != processName) {
            add(processName)
        }
        caller.pid.takeIf { it != 0 }?.let { add("pid $it") }
        caller.executablePath
            .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_EXECUTABLE_PATH_LENGTH)
            ?.let(::add)
    }.takeIf { it.isNotEmpty() }?.joinToString(separator = " • ")

    return AgentApprovalCallerInfo(
        primaryLabel = primaryLabel,
        secondaryLabel = secondaryLabel,
    )
}
