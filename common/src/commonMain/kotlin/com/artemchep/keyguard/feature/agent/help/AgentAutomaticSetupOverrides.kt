package com.artemchep.keyguard.feature.agent.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.agent_auto_setup_overrides_note
import com.artemchep.keyguard.res.agent_auto_setup_overrides_title
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.theme.Dimens
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AgentWizardOverrides(overrides: ImmutableList<AgentSetupOverride>) {
    FlatSimpleNote(
        type = SimpleNote.Type.INFO,
        title = stringResource(Res.string.agent_auto_setup_overrides_title),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.verticalPadding)) {
                Text(
                    stringResource(Res.string.agent_auto_setup_overrides_note),
                    style = MaterialTheme.typography.bodyMedium,
                )
                overrides.forEach { override ->
                    Column {
                        Text(
                            override.setting,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            "${override.path}:${override.line}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        },
    )
}
