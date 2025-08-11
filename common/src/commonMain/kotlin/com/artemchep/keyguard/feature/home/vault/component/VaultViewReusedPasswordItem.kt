package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

@Composable
fun VaultViewReusedPasswordItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.ReusedPassword,
) {
    val contentColor = MaterialTheme.colorScheme.error
    val backgroundColor = MaterialTheme.colorScheme.errorContainer
        .combineAlpha(DisabledEmphasisAlpha)
    FlatItemLayoutExpressive(
        modifier = modifier,
        backgroundColor = backgroundColor,
        shapeState = item.shapeState,
        leading = {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = contentColor,
            )
        },
        content = {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onErrorContainer,
            ) {
                FlatItemTextContent(
                    title = {
                        Text(
                            stringResource(Res.string.reused_password),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    },
                )
            }
        },
        trailing = {
            ChevronIcon()
        },
        onClick = item.onClick,
    )
}
