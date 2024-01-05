package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.DropdownMenuItemFlat
import com.artemchep.keyguard.ui.DropdownMinWidth
import com.artemchep.keyguard.ui.DropdownScopeImpl
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.IconSmallBox
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.warning
import com.artemchep.keyguard.ui.theme.warningContainer
import com.artemchep.keyguard.ui.util.DividerColor

@Composable
fun VaultViewUriItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Uri,
) {
    FlatDropdown(
        modifier = modifier,
        leading = {
            item.icon()
        },
        content = {
            FlatItemTextContent(
                title = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            modifier = Modifier
                                .weight(1f),
                            text = item.title,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 5,
                        )
                        ExpandedIfNotEmptyForRow(
                            item.matchTypeTitle,
                        ) { matchTypeTitle ->
                            Text(
                                modifier = Modifier
                                    .padding(
                                        start = 8.dp,
                                    )
                                    .widthIn(max = 128.dp)
                                    .border(
                                        Dp.Hairline,
                                        DividerColor,
                                        MaterialTheme.shapes.small,
                                    )
                                    .padding(
                                        horizontal = 8.dp,
                                        vertical = 4.dp,
                                    ),
                                text = matchTypeTitle,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.End,
                                maxLines = 2,
                            )
                        }
                    }
                },
                text = if (item.text != null) {
                    // composable
                    {
                        Text(
                            text = item.text,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 5,
                        )
                    }
                } else {
                    null
                },
            )
            ExpandedIfNotEmpty(
                item.warningTitle,
            ) { warningTitle ->
                val contentColor = MaterialTheme.colorScheme.warning
                val backgroundColor = MaterialTheme.colorScheme.warningContainer
                    .combineAlpha(DisabledEmphasisAlpha)
                Row(
                    modifier = Modifier
                        .padding(
                            top = 4.dp,
                        )
                        .background(
                            backgroundColor,
                            MaterialTheme.shapes.small,
                        )
                        .padding(
                            start = 4.dp,
                            top = 4.dp,
                            bottom = 4.dp,
                            end = 4.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        modifier = Modifier
                            .size(14.dp),
                        imageVector = Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = contentColor,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        modifier = Modifier,
                        text = warningTitle,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            var selectedDropdown by remember {
                mutableStateOf<List<ContextItem>>(emptyList())
            }
            if (item.overrides.isNotEmpty()) FlowRow(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.overrides.forEach { override ->
                    val updatedDropdownState = rememberUpdatedState(override.dropdown)
                    UrlOverrideItem(
                        title = override.title,
                        text = override.text,
                        onClick = {
                            selectedDropdown = updatedDropdownState.value
                        },
                    )
                }
            }

            // Inject the dropdown popup to the bottom of the
            // content.
            val onDismissRequest = {
                selectedDropdown = emptyList()
            }
            DropdownMenu(
                expanded = selectedDropdown.isNotEmpty(),
                onDismissRequest = onDismissRequest,
                modifier = Modifier
                    .widthIn(min = DropdownMinWidth),
            ) {
                val scope = DropdownScopeImpl(this, onDismissRequest = onDismissRequest)
                selectedDropdown.forEach { action ->
                    scope.DropdownMenuItemFlat(
                        action = action,
                    )
                }
            }
        },
        dropdown = item.dropdown,
    )
}

@Composable
private fun UrlOverrideItem(
    modifier: Modifier = Modifier,
    title: String,
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(
                    start = 8.dp,
                    end = 8.dp,
                    top = 8.dp,
                    bottom = 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp),
            ) {
                IconSmallBox(
                    main = Icons.Outlined.Terminal,
                )
            }
            Spacer(
                modifier = Modifier
                    .width(8.dp),
            )
            Text(
                modifier = Modifier
                    .widthIn(max = 128.dp)
                    .alignByBaseline(),
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(
                modifier = Modifier
                    .width(8.dp),
            )
            Text(
                modifier = Modifier
                    .widthIn(max = 128.dp)
                    .alignByBaseline(),
                text = text,
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
