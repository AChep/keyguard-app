package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.info
import com.artemchep.keyguard.ui.theme.infoContainer
import com.artemchep.keyguard.ui.theme.onInfoContainer
import org.jetbrains.compose.resources.stringResource

@Composable
fun VaultViewInactivePasskeyItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.InactivePasskey,
) {
    val contentColor = MaterialTheme.colorScheme.info
    val backgroundColor = MaterialTheme.colorScheme.infoContainer
        .combineAlpha(DisabledEmphasisAlpha)
    FlatItemLayoutExpressive(
        modifier = modifier,
        shapeState = item.shapeState,
        backgroundColor = backgroundColor,
        contentColor = MaterialTheme.colorScheme.onInfoContainer,
        leading = {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = contentColor,
            )
        },
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        stringResource(Res.string.passkey_available),
                        style = MaterialTheme.typography.titleSmall,
                    )
                },
            )
        },
        onClick = item.onClick,
    )
}
