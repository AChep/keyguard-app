package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_agent_approval_scope_application
import com.artemchep.keyguard.res.pref_item_agent_approval_scope_application_and_terminal_session
import com.artemchep.keyguard.res.pref_item_agent_approval_scope_connection
import com.artemchep.keyguard.res.pref_item_agent_approval_scope_process
import com.artemchep.keyguard.res.pref_item_agent_approval_scope_table_android_application_and_terminal_session_reuse
import com.artemchep.keyguard.res.pref_item_agent_approval_scope_table_android_process_reuse
import com.artemchep.keyguard.res.pref_item_agent_approval_scope_table_application_and_terminal_session_reuse
import com.artemchep.keyguard.res.pref_item_agent_approval_scope_table_application_reuse
import com.artemchep.keyguard.res.pref_item_agent_approval_scope_table_connection_reuse
import com.artemchep.keyguard.res.pref_item_agent_approval_scope_table_current_connection_only
import com.artemchep.keyguard.res.pref_item_agent_approval_scope_table_not_shared
import com.artemchep.keyguard.res.pref_item_agent_approval_scope_table_process_reuse
import com.artemchep.keyguard.res.pref_item_agent_approval_scope_table_same_process_only
import com.artemchep.keyguard.res.pref_item_agent_approval_scope_table_shared
import org.jetbrains.compose.resources.StringResource

@Immutable
internal data class AgentApprovalScopePresentation(
    val rows: List<AgentApprovalScopePresentationRow>,
) {
    fun row(policy: AgentApprovalCachePolicy): AgentApprovalScopePresentationRow =
        rows.first { it.policy == policy }
}

@Immutable
internal data class AgentApprovalScopePresentationRow(
    val policy: AgentApprovalCachePolicy,
    val titleResource: StringResource,
    val reuseBoundaryResource: StringResource,
    val sameTerminalResource: StringResource,
    val otherTerminalResource: StringResource,
) {
    val isDefault: Boolean
        get() = policy == AgentApprovalCachePolicy.Default
}

internal enum class AgentApprovalScopePresentationPlatform {
    Native,
    Android,
}

internal fun agentApprovalScopePresentation(
    platform: AgentApprovalScopePresentationPlatform,
): AgentApprovalScopePresentation {
    val isAndroid = platform == AgentApprovalScopePresentationPlatform.Android
    val rows = AgentApprovalCachePolicy.entries.map { policy ->
        when (policy) {
            AgentApprovalCachePolicy.Connection -> AgentApprovalScopePresentationRow(
                policy = policy,
                titleResource = Res.string.pref_item_agent_approval_scope_connection,
                reuseBoundaryResource = Res.string.pref_item_agent_approval_scope_table_connection_reuse,
                sameTerminalResource = Res.string.pref_item_agent_approval_scope_table_current_connection_only,
                otherTerminalResource = Res.string.pref_item_agent_approval_scope_table_current_connection_only,
            )

            AgentApprovalCachePolicy.Process -> if (isAndroid) {
                AgentApprovalScopePresentationRow(
                    policy = policy,
                    titleResource = Res.string.pref_item_agent_approval_scope_process,
                    reuseBoundaryResource = Res.string.pref_item_agent_approval_scope_table_android_process_reuse,
                    sameTerminalResource = Res.string.pref_item_agent_approval_scope_table_current_connection_only,
                    otherTerminalResource = Res.string.pref_item_agent_approval_scope_table_current_connection_only,
                )
            } else {
                AgentApprovalScopePresentationRow(
                    policy = policy,
                    titleResource = Res.string.pref_item_agent_approval_scope_process,
                    reuseBoundaryResource = Res.string.pref_item_agent_approval_scope_table_process_reuse,
                    sameTerminalResource = Res.string.pref_item_agent_approval_scope_table_same_process_only,
                    otherTerminalResource = Res.string.pref_item_agent_approval_scope_table_same_process_only,
                )
            }

            AgentApprovalCachePolicy.Application -> AgentApprovalScopePresentationRow(
                policy = policy,
                titleResource = Res.string.pref_item_agent_approval_scope_application,
                reuseBoundaryResource = Res.string.pref_item_agent_approval_scope_table_application_reuse,
                sameTerminalResource = Res.string.pref_item_agent_approval_scope_table_shared,
                otherTerminalResource = Res.string.pref_item_agent_approval_scope_table_shared,
            )

            AgentApprovalCachePolicy.ApplicationAndTerminalSession -> if (isAndroid) {
                AgentApprovalScopePresentationRow(
                    policy = policy,
                    titleResource = Res.string.pref_item_agent_approval_scope_application_and_terminal_session,
                    reuseBoundaryResource =
                        Res.string.pref_item_agent_approval_scope_table_android_application_and_terminal_session_reuse,
                    sameTerminalResource = Res.string.pref_item_agent_approval_scope_table_shared,
                    otherTerminalResource = Res.string.pref_item_agent_approval_scope_table_shared,
                )
            } else {
                AgentApprovalScopePresentationRow(
                    policy = policy,
                    titleResource = Res.string.pref_item_agent_approval_scope_application_and_terminal_session,
                    reuseBoundaryResource =
                        Res.string.pref_item_agent_approval_scope_table_application_and_terminal_session_reuse,
                    sameTerminalResource = Res.string.pref_item_agent_approval_scope_table_shared,
                    otherTerminalResource = Res.string.pref_item_agent_approval_scope_table_not_shared,
                )
            }
        }
    }
    return AgentApprovalScopePresentation(
        rows = rows,
    )
}
