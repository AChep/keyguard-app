package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.onWarningContainer
import com.artemchep.keyguard.ui.theme.warning
import com.artemchep.keyguard.ui.theme.warningContainer
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun VaultViewInactiveTotpItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.InactiveTotp,
) {
    val contentColor = MaterialTheme.colorScheme.warning
    val backgroundColor = MaterialTheme.colorScheme.warningContainer
        .combineAlpha(DisabledEmphasisAlpha)
    FlatDropdown(
        modifier = modifier,
        backgroundColor = backgroundColor,
        leading = {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = null,
                tint = contentColor,
            )
        },
        content = {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onWarningContainer,
            ) {
                FlatItemTextContent(
                    title = {
                        Text(
                            text = stringResource(Res.strings.twofa_available),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    },
                )
            }
        },
        trailing = if (item.chevron) {
            // composable
            {
                ChevronIcon()
            }
        } else {
            null
        },
        onClick = item.onClick,
    )
}
