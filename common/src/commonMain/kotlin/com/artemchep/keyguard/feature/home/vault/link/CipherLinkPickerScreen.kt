package com.artemchep.keyguard.feature.home.vault.link

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.home.vault.component.CompactVaultItem
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

@Composable
fun CipherLinkPickerScreen(
    args: CipherLinkPickerRoute.Args,
    transmitter: RouteResultTransmitter<CipherLinkPickerResult>,
) {
    val state = produceCipherLinkPickerState(
        args = args,
        transmitter = transmitter,
    )
    Dialog(
        icon = icon(Icons.Outlined.Link),
        title = {
            Text(stringResource(Res.string.cipher_link_picker_title))
        },
        contentScrollable = false,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                FlatTextField(
                    modifier = Modifier
                        .padding(horizontal = Dimens.fieldHorizontalPadding),
                    value = state.query,
                    placeholder = stringResource(Res.string.vault_main_search_placeholder),
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                if (state.items.isEmpty()) {
                    Text(
                        modifier = Modifier
                            .padding(horizontal = Dimens.horizontalPadding),
                        text = stringResource(Res.string.items_empty_label),
                        color = LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha),
                    )
                    Spacer(Modifier.height(16.dp))
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 440.dp),
                    ) {
                        items(
                            items = state.items,
                            key = { item -> item.presentation.source.id },
                        ) { item ->
                            CompactVaultItem(
                                item = item.presentation,
                                trailing = {
                                    ChevronIcon()
                                },
                                onClick = item.onClick,
                            )
                        }
                    }
                }
            }
        },
        actions = {
            val updatedOnDeny by rememberUpdatedState(state.onDeny)
            TextButton(
                enabled = updatedOnDeny != null,
                onClick = {
                    updatedOnDeny?.invoke()
                },
            ) {
                Text(stringResource(Res.string.close))
            }
        },
    )
}
