package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.service.environment.EnvironmentMcp
import com.artemchep.keyguard.feature.agent.help.AgentSetupCodeBlock
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.environments_mcp_note
import com.artemchep.keyguard.res.environments_mcp_setup
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingEnvironmentsMcpSetupProvider(
    koinScope: Scope,
) = environmentsSettingProvider {
    settingEnvironmentsMcpSetupProvider(
        environmentMcp = koinScope.get(),
    )
}

fun settingEnvironmentsMcpSetupProvider(
    environmentMcp: EnvironmentMcp,
): SettingComponent = environmentMcp.state
    .map { state ->
        state.configuration.takeIf { state.enabled }
    }
    .distinctUntilChanged()
    .map { configuration ->
        SettingIi(
            platformClasses = environmentsPlatformClasses,
            search = SettingIi.Search(
                group = "developer",
                tokens = listOf(
                    "environments",
                    "mcp",
                    "setup",
                    "configuration",
                    "approval",
                ),
            ),
        ) {
            Column(
                modifier = Modifier
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    modifier = Modifier
                        .padding(horizontal = Dimens.textHorizontalPadding),
                    text = stringResource(Res.string.environments_mcp_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                )
                if (!configuration.isNullOrBlank()) {
                    Spacer(
                        modifier = Modifier
                            .height(16.dp),
                    )
                    Text(
                        modifier = Modifier
                            .padding(horizontal = Dimens.textHorizontalPadding),
                        text = stringResource(Res.string.environments_mcp_setup),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    AgentSetupCodeBlock(
                        text = configuration,
                    )
                }
            }
        }
    }
