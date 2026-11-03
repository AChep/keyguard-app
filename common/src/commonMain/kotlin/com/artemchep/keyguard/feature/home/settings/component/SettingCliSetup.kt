package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.service.cli.CliService
import com.artemchep.keyguard.feature.agent.help.AgentSetupCodeBlock
import com.artemchep.keyguard.feature.cli.cliUsage
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.cli_note
import com.artemchep.keyguard.res.cli_path
import com.artemchep.keyguard.res.cli_usage
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingCliSetupProvider(
    koinScope: Scope,
) = cliSettingProvider {
    settingCliSetupProvider(
        cliService = koinScope.get(),
    )
}

fun settingCliSetupProvider(
    cliService: CliService,
): SettingComponent = cliService.state
    .map { state ->
        state.path.takeIf { state.enabled && state.supported }?.let { path ->
            path to cliUsage(state.socketPath)
        }
    }
    .distinctUntilChanged()
    .map { setup ->
        if (setup == null) {
            return@map null
        }
        val (path, usage) = setup

        SettingIi(
            platformClasses = cliPlatformClasses,
            search = SettingIi.Search(
                group = "developer",
                tokens = listOf(
                    "cli",
                    "command",
                    "run",
                    "setup",
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
                    text = stringResource(Res.string.cli_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                )
                SettingCliCode(
                    title = Res.string.cli_usage,
                    code = usage,
                )
                SettingCliCode(
                    title = Res.string.cli_path,
                    code = path,
                )
            }
        }
    }

@Composable
private fun SettingCliCode(
    title: StringResource,
    code: String,
) {
    Spacer(
        modifier = Modifier
            .height(16.dp),
    )
    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.textHorizontalPadding),
        text = stringResource(title),
        style = MaterialTheme.typography.labelLarge,
    )
    AgentSetupCodeBlock(
        text = code,
    )
}
