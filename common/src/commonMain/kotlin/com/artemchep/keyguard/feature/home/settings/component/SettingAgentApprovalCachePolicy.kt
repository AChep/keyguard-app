package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.PutGpgAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.PutSshAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgPicker
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_agent_approval_scope_table_other_terminal
import com.artemchep.keyguard.res.pref_item_agent_approval_scope_table_reuse_boundary
import com.artemchep.keyguard.res.pref_item_agent_approval_scope_table_same_terminal
import com.artemchep.keyguard.res.pref_item_agent_approval_scope_title
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.TableRowItem
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.util.HorizontalDivider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingSshAgentApprovalCachePolicyProvider(
    directDI: DirectDI,
) = settingAgentApprovalCachePolicyProvider(
    getPolicy = directDI.instance<GetSshAgentApprovalCachePolicy>()(),
    putPolicy = directDI.instance<PutSshAgentApprovalCachePolicy>(),
    windowCoroutineScope = directDI.instance(),
    idPrefix = "settings.sshAgentApprovalCachePolicy",
    platformClasses = listOf(
        Platform.Mobile.Android::class,
        Platform.Desktop.Linux::class,
        Platform.Desktop.MacOS::class,
    ),
)

fun settingGpgAgentApprovalCachePolicyProvider(
    directDI: DirectDI,
) = settingAgentApprovalCachePolicyProvider(
    getPolicy = directDI.instance<GetGpgAgentApprovalCachePolicy>()(),
    putPolicy = directDI.instance<PutGpgAgentApprovalCachePolicy>(),
    windowCoroutineScope = directDI.instance(),
    idPrefix = "settings.gpgAgentApprovalCachePolicy",
    platformClasses = listOf(
        Platform.Desktop.Linux::class,
        Platform.Desktop.MacOS::class,
    ),
)

private fun settingAgentApprovalCachePolicyProvider(
    getPolicy: Flow<AgentApprovalCachePolicy>,
    putPolicy: (AgentApprovalCachePolicy) -> com.artemchep.keyguard.common.io.IO<Unit>,
    windowCoroutineScope: WindowCoroutineScope,
    idPrefix: String,
    platformClasses: List<kotlin.reflect.KClass<out Platform>>,
): SettingComponent {
    val presentationPlatform = if (CurrentPlatform is Platform.Mobile.Android) {
        AgentApprovalScopePresentationPlatform.Android
    } else {
        AgentApprovalScopePresentationPlatform.Native
    }
    val presentation = agentApprovalScopePresentation(presentationPlatform)
    return getPolicy.map { policy ->
        val dropdown = presentation.rows.map { row ->
            FlatItemAction(
                id = "$idPrefix.${row.policy.storageKey}",
                title = TextHolder.Res(row.titleResource),
                selected = row.policy == policy,
                onClick = {
                    putPolicy(row.policy).launchIn(windowCoroutineScope)
                },
            )
        }

        SettingIi(
            platformClasses = platformClasses,
            search = SettingIi.Search(
                group = "security",
                tokens = listOf(
                    "agent",
                    "approval",
                    "authorization",
                    "scope",
                    "application",
                    "process",
                    "terminal",
                    "session",
                    "connection",
                ),
            ),
        ) {
            SettingAgentApprovalCachePolicy(
                policy = policy,
                presentation = presentation,
                dropdown = dropdown,
            )
        }
    }
}

@Composable
private fun SettingAgentApprovalCachePolicy(
    policy: AgentApprovalCachePolicy,
    presentation: AgentApprovalScopePresentation,
    dropdown: List<FlatItemAction>,
) {
    LocalSettingPaneComponents.current.KgPicker(
        icon = null,
        title = stringResource(Res.string.pref_item_agent_approval_scope_title),
        footer = {
            AgentApprovalScopeComparison(
                selectedPolicy = policy,
                presentation = presentation,
            )
        },
        dropdown = dropdown,
    )
}

@Composable
private fun AgentApprovalScopeComparison(
    selectedPolicy: AgentApprovalCachePolicy,
    presentation: AgentApprovalScopePresentation,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = getSettingsTextStartPadding(),
                end = Dimens.contentPadding,
            )
            .padding(
                top = 8.dp,
                bottom = 8.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val titleWeight = 0.7f
        val selectedPresentation = presentation.row(selectedPolicy)
        TableRowItem(
            modifier = Modifier
                .fillMaxWidth(),
            title = stringResource(Res.string.pref_item_agent_approval_scope_table_reuse_boundary),
            titleWeight = titleWeight,
            text = stringResource(selectedPresentation.reuseBoundaryResource),
        )
        HorizontalDivider()
        TableRowItem(
            modifier = Modifier
                .fillMaxWidth(),
            title = stringResource(Res.string.pref_item_agent_approval_scope_table_same_terminal),
            titleWeight = titleWeight,
            text = stringResource(selectedPresentation.sameTerminalResource),
        )
        HorizontalDivider()
        TableRowItem(
            modifier = Modifier
                .fillMaxWidth(),
            title = stringResource(Res.string.pref_item_agent_approval_scope_table_other_terminal),
            titleWeight = titleWeight,
            text = stringResource(selectedPresentation.otherTerminalResource),
        )
    }
}
