package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun VaultViewPasskeyItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Passkey,
) {
    FlatItemLayout(
        modifier = modifier,
        elevation = 1.dp,
        leading = icon<RowScope>(Icons.Outlined.Key),
        content = {
            val columnLayout = item.onUse != null
            if (columnLayout) {
                Column {
                    if (item.source.userDisplayName != null) {
                        TextUserName(
                            text = item.source.userDisplayName,
                        )
                    }
                    TextRpId(
                        text = item.source.rpId,
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                ) {
                    if (item.source.userDisplayName != null) {
                        TextUserName(
                            modifier = Modifier
                                .alignByBaseline()
                                .weight(0.6f, fill = false),
                            text = item.source.userDisplayName,
                        )
                        Spacer(
                            modifier = Modifier
                                .width(8.dp),
                        )
                    }
                    TextRpId(
                        modifier = Modifier
                            .alignByBaseline()
                            .weight(0.4f, fill = false),
                        text = item.source.rpId,
                    )
                }
            }
        },
        trailing = if (item.onUse != null) {
            // composable
            {
                val updatedOnUse by rememberUpdatedState(item.onUse)
                Button(
                    onClick = {
                        updatedOnUse.invoke()
                    },
                ) {
                    Icon(Icons.Outlined.Check, null)
                    Spacer(
                        modifier = Modifier
                            .width(Dimens.buttonIconPadding),
                    )
                    Text(
                        text = stringResource(Res.strings.passkey_use_short),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            null
        },
        onClick = item.onClick,
    )
}

@Composable
private fun TextUserName(
    modifier: Modifier = Modifier,
    text: String,
) {
    Text(
        modifier = modifier,
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TextRpId(
    modifier: Modifier = Modifier,
    text: String,
) {
    Text(
        modifier = modifier,
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = LocalContentColor.current
            .combineAlpha(MediumEmphasisAlpha),
    )
}
