package com.artemchep.keyguard.feature.home.settings.component

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
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentApprovalScopePresentationTest {
    @Test
    fun `desktop presentation exposes terminal comparison columns`() {
        val presentation = agentApprovalScopePresentation(
            AgentApprovalScopePresentationPlatform.Native,
        )

        assertEquals(
            listOf(
                row(
                    policy = AgentApprovalCachePolicy.Connection,
                    title = Res.string.pref_item_agent_approval_scope_connection,
                    reuseBoundary = Res.string.pref_item_agent_approval_scope_table_connection_reuse,
                    sameTerminal = Res.string.pref_item_agent_approval_scope_table_current_connection_only,
                    otherTerminal = Res.string.pref_item_agent_approval_scope_table_current_connection_only,
                ),
                row(
                    policy = AgentApprovalCachePolicy.Process,
                    title = Res.string.pref_item_agent_approval_scope_process,
                    reuseBoundary = Res.string.pref_item_agent_approval_scope_table_process_reuse,
                    sameTerminal = Res.string.pref_item_agent_approval_scope_table_same_process_only,
                    otherTerminal = Res.string.pref_item_agent_approval_scope_table_same_process_only,
                ),
                row(
                    policy = AgentApprovalCachePolicy.Application,
                    title = Res.string.pref_item_agent_approval_scope_application,
                    reuseBoundary = Res.string.pref_item_agent_approval_scope_table_application_reuse,
                    sameTerminal = Res.string.pref_item_agent_approval_scope_table_shared,
                    otherTerminal = Res.string.pref_item_agent_approval_scope_table_shared,
                ),
                row(
                    policy = AgentApprovalCachePolicy.ApplicationAndTerminalSession,
                    title = Res.string.pref_item_agent_approval_scope_application_and_terminal_session,
                    reuseBoundary =
                        Res.string.pref_item_agent_approval_scope_table_application_and_terminal_session_reuse,
                    sameTerminal = Res.string.pref_item_agent_approval_scope_table_shared,
                    otherTerminal = Res.string.pref_item_agent_approval_scope_table_not_shared,
                ),
            ),
            presentation.rows,
        )
        assertEquals(
            listOf(AgentApprovalCachePolicy.ApplicationAndTerminalSession),
            presentation.rows.filter { it.isDefault }.map { it.policy },
        )
    }

    @Test
    fun `android presentation describes effective fallbacks`() {
        val presentation = agentApprovalScopePresentation(
            AgentApprovalScopePresentationPlatform.Android,
        )

        assertEquals(
            listOf(
                row(
                    policy = AgentApprovalCachePolicy.Connection,
                    title = Res.string.pref_item_agent_approval_scope_connection,
                    reuseBoundary = Res.string.pref_item_agent_approval_scope_table_connection_reuse,
                    sameTerminal = Res.string.pref_item_agent_approval_scope_table_current_connection_only,
                    otherTerminal = Res.string.pref_item_agent_approval_scope_table_current_connection_only,
                ),
                row(
                    policy = AgentApprovalCachePolicy.Process,
                    title = Res.string.pref_item_agent_approval_scope_process,
                    reuseBoundary = Res.string.pref_item_agent_approval_scope_table_android_process_reuse,
                    sameTerminal = Res.string.pref_item_agent_approval_scope_table_current_connection_only,
                    otherTerminal = Res.string.pref_item_agent_approval_scope_table_current_connection_only,
                ),
                row(
                    policy = AgentApprovalCachePolicy.Application,
                    title = Res.string.pref_item_agent_approval_scope_application,
                    reuseBoundary = Res.string.pref_item_agent_approval_scope_table_application_reuse,
                    sameTerminal = Res.string.pref_item_agent_approval_scope_table_shared,
                    otherTerminal = Res.string.pref_item_agent_approval_scope_table_shared,
                ),
                row(
                    policy = AgentApprovalCachePolicy.ApplicationAndTerminalSession,
                    title = Res.string.pref_item_agent_approval_scope_application_and_terminal_session,
                    reuseBoundary =
                        Res.string.pref_item_agent_approval_scope_table_android_application_and_terminal_session_reuse,
                    sameTerminal = Res.string.pref_item_agent_approval_scope_table_shared,
                    otherTerminal = Res.string.pref_item_agent_approval_scope_table_shared,
                ),
            ),
            presentation.rows,
        )
        assertEquals(
            listOf(AgentApprovalCachePolicy.ApplicationAndTerminalSession),
            presentation.rows.filter { it.isDefault }.map { it.policy },
        )
    }

    private fun row(
        policy: AgentApprovalCachePolicy,
        title: org.jetbrains.compose.resources.StringResource,
        reuseBoundary: org.jetbrains.compose.resources.StringResource,
        sameTerminal: org.jetbrains.compose.resources.StringResource,
        otherTerminal: org.jetbrains.compose.resources.StringResource,
    ) = AgentApprovalScopePresentationRow(
        policy = policy,
        titleResource = title,
        reuseBoundaryResource = reuseBoundary,
        sameTerminalResource = sameTerminal,
        otherTerminalResource = otherTerminal,
    )
}
