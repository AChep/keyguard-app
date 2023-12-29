package com.artemchep.keyguard.feature.attachments.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.attachments.model.AttachmentItem
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountSI
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.icons.AttachmentIcon
import com.artemchep.keyguard.ui.theme.info
import com.artemchep.keyguard.ui.theme.infoContainer

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItemAttachment(
    modifier: Modifier,
    item: AttachmentItem,
) {
    val downloadStatusState = item.statusState.collectAsState()
    val selectableState = item.selectableState.collectAsState()
    val actionsState = item.actionsState.collectAsState()
    ItemAttachmentLayout(
        modifier = modifier,
        name = item.name,
        size = item.size,
        dropdown = actionsState.value,
        previewUrlProvider = { downloadStatusState.value.previewUrl },
        downloadedProvider = {
            downloadStatusState.value is AttachmentItem.Status.Downloaded
        },
        selectableState = selectableState,
        content = {
            ExpandedIfNotEmpty(
                // If the download progress does not exist then
                // do not show the visuals.
                valueOrNull = downloadStatusState.value as? AttachmentItem.Status.Loading,
            ) { downloadProgress ->
                Column {
                    Spacer(
                        modifier = Modifier
                            .height(8.dp),
                    )

                    val downloaded = downloadProgress.downloaded
                    val total = downloadProgress.total

                    val downloadedRatio = if (
                        downloaded != null &&
                        total != null
                    ) {
                        val p = downloaded.toDouble() / total.toDouble()
                        p.toFloat().coerceIn(0f..1f)
                    } else {
                        null
                    }
                    Row(
                        modifier = Modifier
                            .heightIn(min = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val downloadedHumanReadable = downloaded?.let(::humanReadableByteCountSI)
                        ExpandedIfNotEmptyForRow(
                            valueOrNull = downloadedHumanReadable,
                        ) { text ->
                            Text(
                                modifier = Modifier
                                    .widthIn(min = 64.dp)
                                    .padding(end = 8.dp)
                                    .animateContentSize(),
                                text = text,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Crossfade(
                            targetState = downloadedRatio != null,
                        ) { hasProgress ->
                            if (hasProgress) {
                                val updatedProgressState = remember {
                                    mutableStateOf(0f)
                                }
                                if (downloadedRatio != null) {
                                    updatedProgressState.value =
                                        downloadedRatio
                                }
                                val p = animateFloatAsState(updatedProgressState.value)
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth(),
                                    progress = p.value,
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
            ExpandedIfNotEmpty(
                // If the download progress does not exist then
                // do not show the visuals.
                valueOrNull = downloadStatusState.value as? AttachmentItem.Status.Failed,
            ) { downloadFailed ->
                FlowRow(
                    modifier = Modifier
                        .padding(
                            top = 4.dp,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    val errorContentColor = MaterialTheme.colorScheme.error
                    val errorBackgroundColor = MaterialTheme.colorScheme.errorContainer
                    val infoContentColor = MaterialTheme.colorScheme.info
                    val infoBackgroundColor = MaterialTheme.colorScheme.infoContainer
                    Row(
                        modifier = Modifier
                            .background(
                                errorBackgroundColor,
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
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = errorContentColor,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            modifier = Modifier,
                            text = "Downloading failed",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    ExpandedIfNotEmptyForRow(
                        valueOrNull = Unit.takeIf { downloadFailed.autoResume },
                    ) {
                        Row(
                            modifier = Modifier
                                .background(
                                    infoBackgroundColor,
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
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = infoContentColor,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                modifier = Modifier,
                                text = "Auto-resumes",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ItemAttachmentLayout(
    modifier: Modifier,
    name: String,
    size: String? = null,
    dropdown: List<ContextItem>,
    previewUrlProvider: @Composable () -> String?,
    downloadedProvider: @Composable () -> Boolean,
    selectableState: State<SelectableItemState>,
    content: (@Composable () -> Unit)? = null,
) {
    val selectable = selectableState.value
    val selected = selectable.selected
    val backgroundColor =
        if (selectable.selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    FlatDropdown(
        modifier = modifier,
        backgroundColor = backgroundColor,
        content = {
            FlatItemTextContent(
                title = {
                    Text(name)
                },
                text = if (size != null) {
                    // composable
                    {
                        Text(size)
                    }
                } else {
                    null
                },
            )
            content?.invoke()
        },
        leading = {
            Box {
                val url = previewUrlProvider()
                val downloaded = downloadedProvider()
                AttachmentIcon(
                    uri = url,
                    name = name,
                    encrypted = false,
                )
                Row(
                    modifier = Modifier
                        .offset(x = 6.dp, y = 6.dp)
                        .align(Alignment.BottomEnd),
                ) {
                    AnimatedVisibility(
                        visible = downloaded,
                        enter = fadeIn() + scaleIn(),
                        exit = scaleOut() + fadeOut(),
                    ) {
                        Icon(
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                    MaterialTheme.shapes.extraSmall,
                                )
                                .size(16.dp)
                                .padding(1.dp),
                            imageVector = Icons.Outlined.DownloadDone,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            contentDescription = null,
                        )
                    }
                }
            }
        },
        trailing = {
            Crossfade(
                targetState = selectable.selecting,
            ) {
                if (it) {
                    Checkbox(
                        modifier = Modifier
                            .sizeIn(
                                minWidth = 48.dp,
                                minHeight = 48.dp,
                            ),
                        checked = selected,
                        onCheckedChange = null,
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .sizeIn(
                                minWidth = 48.dp,
                                minHeight = 48.dp,
                            ),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val onLaunchAction = remember(dropdown) {
                            dropdown
                                .firstNotNullOfOrNull {
                                    val action = it as? FlatItemAction
                                    action?.takeIf { it.type == FlatItemAction.Type.VIEW }
                                }
                        }
                        ExpandedIfNotEmptyForRow(
                            valueOrNull = onLaunchAction,
                        ) { action ->
                            val onCopy = action.onClick
                            IconButton(
                                enabled = onCopy != null,
                                onClick = {
                                    onCopy?.invoke()
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Launch,
                                    contentDescription = null,
                                )
                            }
                        }

                        val onDownloadAction = remember(dropdown) {
                            dropdown
                                .firstNotNullOfOrNull {
                                    val action = it as? FlatItemAction
                                    action?.takeIf { it.type == FlatItemAction.Type.DOWNLOAD }
                                }
                        }
                        ExpandedIfNotEmptyForRow(
                            valueOrNull = onDownloadAction,
                        ) { action ->
                            val onCopy = action.onClick
                            IconButton(
                                enabled = onCopy != null,
                                onClick = {
                                    onCopy?.invoke()
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Download,
                                    contentDescription = null,
                                )
                            }
                        }
                    }
                }
            }
        },
        dropdown = dropdown,
        onClick = selectable.onClick,
        onLongClick = selectable.onLongClick,
        enabled = true,
    )
}
