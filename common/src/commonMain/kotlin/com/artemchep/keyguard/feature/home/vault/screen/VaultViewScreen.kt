@file:OptIn(ExperimentalMaterial3Api::class)

package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.home.vault.component.VaultItemIcon2
import com.artemchep.keyguard.feature.home.vault.component.VaultViewItem
import com.artemchep.keyguard.feature.home.vault.component.rememberSecretAccentColor
import com.artemchep.keyguard.feature.home.vault.component.surfaceColorAtElevationSemi
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.ui.rememberListScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.OptionallyKeepScreenOnEffect
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.focus.ListFocus
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.text.AutoSizeText
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.tooltip.Tooltip
import org.jetbrains.compose.resources.stringResource

private const val EDIT_FOCUS_KEY = "toolbar.edit"

private const val OPTIONS_FOCUS_KEY = "toolbar.options"

@Composable
fun VaultViewScreen(
    itemId: String,
    accountId: String,
    focusFirstField: Boolean = false,
) {
    OptionallyKeepScreenOnEffect()

    val state = vaultViewScreenState(
        mode = LocalAppMode.current,
        contentColor = LocalContentColor.current,
        disabledContentColor = LocalContentColor.current
            .combineAlpha(MediumEmphasisAlpha),
        itemId = itemId,
        accountId = accountId,
    )
    VaultViewScreenContent(
        state = state,
        focusFirstField = focusFirstField,
    )
}

/**
 * The item of the [state]. Arrow keys move between its fields, and Up
 * from the top field enters the toolbar. In keyboard input mode the
 * item focuses the field or the toolbar button its entry focused last,
 * such as when a screen opened on top of it closes. With nothing focused
 * yet, [focusFirstField] focuses its first field once the field shows.
 *
 * The scroll position and the toolbar state are kept while the entry
 * of the item is alive. A new entry starts at the top.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultViewScreenContent(
    state: VaultViewState,
    focusFirstField: Boolean = false,
) {
    // Empty while the item loads: its skeleton
    // takes no focus and saves no scroll position.
    val listKeys = remember(state.content) {
        (state.content as? VaultViewState.Content.Cipher)
            ?.items
            ?.map { it.id }
            .orEmpty()
    }
    val (listState, scrollBehavior, focus) = rememberListScreenState(
        listKeys = listKeys,
        focusFirstOnEnter = focusFirstField,
    )
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    VaultViewTitle(
                        state = state,
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                actions = {
                    VaultViewTitleActions(
                        state = state,
                        focus = focus,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = run {
            val action = (state.content as? VaultViewState.Content.Cipher)
                ?.primaryAction
            val fabState = if (action != null) {
                FabState(
                    onClick = action.onClick,
                    model = action,
                )
            } else {
                null
            }
            rememberUpdatedState(newValue = fabState)
        },
        floatingActionButton = {
            val action = this.state.value?.model as? FlatItemAction
            if (action != null) {
                DefaultFab(
                    icon = {
                        action.leading?.invoke()
                    },
                    text = {
                        Text(
                            text = textResource(action.title),
                        )
                    },
                )
            }
        },
        listModifier = focus.listModifier,
        listState = listState,
        contentFocus = focus.rememberScaffoldContentFocus(
            topBar = true,
        ),
    ) {
        when (state.content) {
            is VaultViewState.Content.Loading -> {
                // Show a bunch of skeleton items, so it makes an impression of a
                // fully loaded screen.
                for (i in 0..1) {
                    item(i) {
                        val contentColor =
                            LocalContentColor.current.copy(alpha = DisabledEmphasisAlpha)
                        FlatItemLayout(
                            modifier = Modifier
                                .shimmer(),
                            backgroundColor = contentColor.copy(alpha = 0.08f),
                            content = {
                                Box(
                                    Modifier
                                        .height(13.dp)
                                        .fillMaxWidth(0.15f)
                                        .clip(MaterialTheme.shapes.medium)
                                        .background(contentColor.copy(alpha = 0.2f)),
                                )
                                Box(
                                    Modifier
                                        .padding(top = 4.dp)
                                        .height(18.dp)
                                        .fillMaxWidth(0.38f)
                                        .clip(MaterialTheme.shapes.medium)
                                        .background(contentColor),
                                )
                            },
                        )
                    }
                }
                item(-1) {
                    val contentColor =
                        LocalContentColor.current.copy(alpha = DisabledEmphasisAlpha)
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .shimmer()
                                .height(10.dp)
                                .fillMaxWidth(0.38f)
                                .clip(MaterialTheme.shapes.medium)
                                .background(contentColor.copy(alpha = 0.23f)),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .shimmer()
                                .height(10.dp)
                                .fillMaxWidth(0.38f)
                                .clip(MaterialTheme.shapes.medium)
                                .background(contentColor.copy(alpha = 0.23f)),
                        )
                    }
                }
            }

            is VaultViewState.Content.Cipher -> {
                val list = state.content.items
                if (list.isEmpty()) {
                    item("header.empty") {
                        NoItemsPlaceholder()
                    }
                }
                items(
                    items = list,
                    key = { model -> model.id },
                ) { model ->
                    VaultViewItem(
                        // Each renderer puts it on its root.
                        modifier = focus.target(model.id),
                        item = model,
                    )
                }
            }

            else -> {
                // Do nothing.
            }
        }
    }
}

@Composable
private fun VaultViewTitle(
    state: VaultViewState,
) = Row(
    verticalAlignment = Alignment.CenterVertically,
) {
    val header = state.toVaultViewHeaderState()
    val shimmerColor = LocalContentColor.current
        .combineAlpha(DisabledEmphasisAlpha)

    val avatarBackground = when (state.content) {
        is VaultViewState.Content.Loading -> shimmerColor
        is VaultViewState.Content.NotFound -> shimmerColor
        is VaultViewState.Content.Cipher -> {
            val avatarIcon = header.icon
            val avatarBackground = if (
                (avatarIcon !is VaultItemIcon.VectorIcon &&
                        avatarIcon !is VaultItemIcon.TextIcon) ||
                !header.hasRemoteService
            ) {
                val elevation = LocalAbsoluteTonalElevation.current + 8.dp
                MaterialTheme.colorScheme
                    .surfaceColorAtElevationSemi(elevation = elevation)
                    .combineAlpha(LocalContentColor.current.alpha)
            } else {
                rememberSecretAccentColor(
                    accentLight = header.accentLight,
                    accentDark = header.accentDark,
                )
            }
            avatarBackground
        }
    }
    Avatar(
        modifier = Modifier
            .then(
                if (header.isLoading) {
                    Modifier
                        .shimmer()
                } else {
                    Modifier
                },
            ),
        color = avatarBackground,
    ) {
        val icon = header.icon
        if (icon != null) {
            VaultItemIcon2(
                icon,
                modifier = Modifier
                    .alpha(LocalContentColor.current.alpha),
            )
        }
    }
    Spacer(modifier = Modifier.width(16.dp))
    when (state.content) {
        is VaultViewState.Content.Loading -> {
            SkeletonText(
                modifier = Modifier
                    .fillMaxWidth(0.33f),
                emphasis = MediumEmphasisAlpha,
            )
            // Apparently a text has some sort of margins?
            Text("")
        }

        is VaultViewState.Content.Cipher -> {
            val title = header.name
                ?.takeUnless { it.isEmpty() }
            Tooltip(
                valueOrNull = title,
                tooltip = { text ->
                    Text(
                        text,
                        maxLines = 8,
                    )
                },
            ) {
                if (title != null) {
                    AutoSizeText(
                        text = title,
                        maxLines = 2,
                        minTextSize = MaterialTheme.typography.titleSmall.fontSize,
                        maxTextSize = LocalTextStyle.current.fontSize,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = stringResource(Res.string.empty_value),
                        color = LocalContentColor.current
                            .combineAlpha(DisabledEmphasisAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        is VaultViewState.Content.NotFound -> {
            Text(
                text = "Not found",
                color = MaterialTheme.colorScheme.error
                    .combineAlpha(LocalContentColor.current.alpha),
            )
        }
    }
    Spacer(modifier = Modifier.width(8.dp))
}

/**
 * The actions of a loaded item. Closing a screen that Edit or
 * the options opened focuses the button again, see [ListFocus.target].
 */
@Composable
private fun RowScope.VaultViewTitleActions(
    state: VaultViewState,
    focus: ListFocus,
) {
    val content = state.content as? VaultViewState.Content.Cipher
        ?: return
    VaultViewCipherTitleActions(
        state = content,
        editModifier = focus.target(EDIT_FOCUS_KEY, inList = false),
        optionsModifier = focus.target(OPTIONS_FOCUS_KEY, inList = false),
    )
}

@Composable
fun NoItemsPlaceholder() {
    EmptyView(
        icon = {
            Icon(Icons.Outlined.SearchOff, null)
        },
        text = {
            Text(text = "No items")
        },
    )
}
