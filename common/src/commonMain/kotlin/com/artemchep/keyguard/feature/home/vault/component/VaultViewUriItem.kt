package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import arrow.core.Either
import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.service.app.parser.AppStoreListingInfo
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.error_failed_unknown
import com.artemchep.keyguard.res.error_not_found
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.DropdownMenuItemFlat
import com.artemchep.keyguard.ui.DropdownScopeImpl
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.KeyguardDropdownMenu
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.FaviconIcon
import com.artemchep.keyguard.ui.icons.IconSmallBox
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.warning
import com.artemchep.keyguard.ui.theme.warningContainer
import com.artemchep.keyguard.ui.util.DividerColor
import io.ktor.http.HttpStatusCode
import org.jetbrains.compose.resources.stringResource

@Composable
fun VaultViewUriItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Uri,
) {
    FlatDropdownSimpleExpressive(
        modifier = modifier,
        shapeState = item.shapeState,
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
        },
        footer = {
            var selectedDropdown by remember {
                mutableStateOf<List<ContextItem>>(emptyList())
            }
            if (item.overrides.isNotEmpty()) FlowRow(
                modifier = Modifier
                    .padding(
                        top = 8.dp,
                        bottom = 8.dp,
                        end = Dimens.contentPadding,
                        start = Dimens.contentPadding + 16.dp + 24.dp,
                    )
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.overrides.forEach { override ->
                    val updatedDropdownState = rememberUpdatedState(override.dropdown)
                    UrlOverrideItem(
                        title = override.title,
                        text = override.text,
                        error = override.error,
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
            KeyguardDropdownMenu(
                expanded = selectedDropdown.isNotEmpty(),
                onDismissRequest = onDismissRequest,
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
    error: Boolean = false,
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
            val contentColor = if (error) {
                MaterialTheme.colorScheme.error
            } else {
                LocalContentColor.current
            }
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
                color = contentColor
                    .combineAlpha(MediumEmphasisAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun UrlAppStoreListings(
    modifier: Modifier = Modifier,
    listings: List<VaultViewItem.Uri.AppStoreListing>,
) {
    FlowRow(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(
                top = 8.dp,
                bottom = 8.dp,
                end = 16.dp,
                start = 16.dp,
            )
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listings.forEach { listing ->
            val state = listing.state.collectAsState(null)
            val value = state.value
            if (value != null) {
                UrlAppStoreListingItem(
                    modifier = Modifier
                        .width(144.dp),
                    store = listing.store,
                    state = value,
                    onClick = listing.onClick,
                )
            } else {
                UrlAppStoreListingSkeletonItem(
                    modifier = Modifier
                        .width(144.dp),
                    store = listing.store,
                )
            }
        }
    }
}

@Composable
private fun UrlAppStoreListingItem(
    modifier: Modifier = Modifier,
    store: String,
    state: Either<Throwable, AppStoreListingInfo?>,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
    ) {
        val enabled = state.isRight { it != null }
        val alpha = if (enabled) {
            1f
        } else DisabledEmphasisAlpha
        Column(
            modifier = Modifier
                .alpha(alpha)
                .clickable(
                    enabled = enabled,
                    onClick = onClick,
                )
                .padding(
                    start = 8.dp,
                    end = 8.dp,
                    top = 8.dp,
                    bottom = 8.dp,
                ),
        ) {
            val contentColor = LocalContentColor.current
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp),
                ) {
                    FaviconIcon(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        imageModel = {
                            state.getOrNull()?.iconUrl
                        },
                    )
                }
                Text(
                    modifier = Modifier
                        .widthIn(max = 196.dp),
                    text = store,
                    color = contentColor
                        .combineAlpha(MediumEmphasisAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(
                modifier = Modifier
                    .height(8.dp),
            )
            val title = state.fold(
                ifLeft = { e ->
                    if (e is HttpException && e.statusCode == HttpStatusCode.NotFound) {
                        stringResource(Res.string.error_not_found)
                    } else e.message
                },
                ifRight = { it?.title },
            ) ?: stringResource(Res.string.error_failed_unknown)
            Text(
                modifier = Modifier
                    .widthIn(max = 196.dp),
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                modifier = Modifier
                    .widthIn(max = 196.dp),
                text = state.getOrNull()?.summary.orEmpty(),
                color = contentColor
                    .combineAlpha(MediumEmphasisAlpha),
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun UrlAppStoreListingSkeletonItem(
    modifier: Modifier = Modifier,
    store: String,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(
                    start = 8.dp,
                    end = 8.dp,
                    top = 8.dp,
                    bottom = 8.dp,
                ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val contentColor = run {
                    val color = LocalContentColor.current
                    color.combineAlpha(DisabledEmphasisAlpha)
                }
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .shimmer()
                        .background(contentColor),
                )
                Text(
                    modifier = Modifier
                        .widthIn(max = 196.dp),
                    text = store,
                    color = contentColor
                        .combineAlpha(MediumEmphasisAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(
                modifier = Modifier
                    .height(8.dp),
            )
            SkeletonText(
                modifier = Modifier
                    .fillMaxWidth(0.66f),
                style = MaterialTheme.typography.titleSmall,
            )
            SkeletonText(
                modifier = Modifier
                    .fillMaxWidth(0.8f),
                style = MaterialTheme.typography.bodySmall,
                emphasis = MediumEmphasisAlpha,
            )
            SkeletonText(
                modifier = Modifier
                    .fillMaxWidth(0.3f),
                style = MaterialTheme.typography.bodySmall,
                emphasis = MediumEmphasisAlpha,
            )
        }
    }
}
