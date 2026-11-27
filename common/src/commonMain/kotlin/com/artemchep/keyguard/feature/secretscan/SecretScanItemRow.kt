package com.artemchep.keyguard.feature.secretscan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.AhLayout
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A finding with its chips. The review rows of `feature/transfer` badge
 * credential kinds only, while a finding needs counts and states.
 */
@Composable
internal fun SecretScanItemRow(
    item: SecretScanState.Item,
    onSelectedChange: ((String, Boolean) -> Unit)?,
) {
    val enabled = onSelectedChange != null
    val onSelectedChangeState by rememberUpdatedState(onSelectedChange)
    FlatItemLayoutExpressive(
        shapeState = item.shapeState,
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = item.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                text = {
                    SecretScanChips(
                        chips = item.chips,
                    )
                },
            )
        },
        trailing = {
            Checkbox(
                checked = item.selected,
                enabled = enabled,
                onCheckedChange = { value ->
                    onSelectedChangeState?.invoke(item.key, value)
                },
            )
        },
        onClick = if (enabled) {
            {
                onSelectedChangeState?.invoke(item.key, !item.selected)
            }
        } else {
            null
        },
        enabled = true,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecretScanChips(
    chips: List<SecretScanState.Chip>,
) {
    FlowRow(
        modifier = Modifier
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { chip ->
            SecretScanChip(
                chip = chip,
            )
        }
    }
}

@Composable
private fun SecretScanChip(
    chip: SecretScanState.Chip,
) {
    val label = when (chip) {
        SecretScanState.Chip.NoPassphrase -> stringResource(Res.string.secret_scan_chip_no_passphrase)
        SecretScanState.Chip.Passphrase -> stringResource(Res.string.secret_scan_chip_passphrase)
        SecretScanState.Chip.InVault -> stringResource(Res.string.secret_scan_chip_in_vault)
        is SecretScanState.Chip.Variables -> pluralStringResource(
            Res.plurals.secret_scan_variables_plural,
            chip.count,
            chip.count,
        )
    }
    val inVault = chip == SecretScanState.Chip.InVault
    val backgroundColor = if (inVault) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (inVault) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    AhLayout(
        contentColor = contentColor,
        backgroundColor = backgroundColor
            .combineAlpha(DisabledEmphasisAlpha),
    ) {
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
