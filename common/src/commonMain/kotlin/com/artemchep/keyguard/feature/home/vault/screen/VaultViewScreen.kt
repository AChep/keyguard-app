@file:OptIn(ExperimentalMaterial3Api::class)

package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.home.vault.component.VaultItemIcon2
import com.artemchep.keyguard.feature.home.vault.component.VaultViewItem
import com.artemchep.keyguard.feature.home.vault.component.rememberSecretAccentColor
import com.artemchep.keyguard.feature.home.vault.component.surfaceColorAtElevationSemi
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.OptionallyKeepScreenOnEffect
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.button.FavouriteToggleButton
import com.artemchep.keyguard.ui.icons.OfflineIcon
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.text.AutoSizeText
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun VaultViewScreen(
    itemId: String,
    accountId: String,
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
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultViewScreenContent(
    state: VaultViewState,
) {
    val listState = rememberSaveable(
        0,
        saver = LazyListState.Saver,
    ) {
        LazyListState(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
        )
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
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
                            text = action.title,
                        )
                    },
                )
            }
        },
        listState = listState,
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
                        modifier = Modifier,
                        // .animateItemPlacement(),
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
    val isLoading = state.content is VaultViewState.Content.Loading
    val shimmerColor = LocalContentColor.current
        .combineAlpha(DisabledEmphasisAlpha)

    val avatarBackground = when (state.content) {
        is VaultViewState.Content.Loading -> shimmerColor
        is VaultViewState.Content.NotFound -> shimmerColor
        is VaultViewState.Content.Cipher -> {
            val avatarIcon = state.content.icon
            val avatarBackground = if (
                (avatarIcon !is VaultItemIcon.VectorIcon &&
                        avatarIcon !is VaultItemIcon.TextIcon) ||
                state.content.data.service.remote == null
            ) {
                val elevation = LocalAbsoluteTonalElevation.current + 8.dp
                MaterialTheme.colorScheme
                    .surfaceColorAtElevationSemi(elevation = elevation)
                    .combineAlpha(LocalContentColor.current.alpha)
            } else {
                rememberSecretAccentColor(
                    accentLight = state.content.data.accentLight,
                    accentDark = state.content.data.accentDark,
                )
            }
            avatarBackground
        }
    }
    Avatar(
        modifier = Modifier
            .then(
                if (isLoading) {
                    Modifier
                        .shimmer()
                } else {
                    Modifier
                },
            ),
        color = avatarBackground,
    ) {
        val icon = VaultViewState.content.cipher.icon.getOrNull(state)
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
            val title = state.content.data.name
                .takeUnless { it.isEmpty() }
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
                    text = stringResource(Res.strings.empty_value),
                    color = LocalContentColor.current
                        .combineAlpha(DisabledEmphasisAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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

@Composable
private fun RowScope.VaultViewTitleActions(
    state: VaultViewState,
) {
    if (state.content is VaultViewState.Content.Cipher) {
        val elevated = state.content.locked.collectAsState()
        AnimatedVisibility(
            modifier = Modifier
                .alpha(LocalContentColor.current.alpha),
            visible = !elevated.value,
        ) {
            Icon(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .alpha(DisabledEmphasisAlpha),
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
            )
        }
        val synced = state.content.synced
        AnimatedVisibility(
            modifier = Modifier
                .alpha(LocalContentColor.current.alpha),
            visible = !synced,
        ) {
            OfflineIcon(
                modifier = Modifier
                    .minimumInteractiveComponentSize(),
            )
        }
        val favorite = state.content.data.favorite
        FavouriteToggleButton(
            favorite = favorite,
            onChange = state.content.onFavourite,
        )
        IconButton(
            onClick = {
                state.content.onEdit?.invoke()
            },
            enabled = state.content.onEdit != null,
        ) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = null,
            )
        }
        OptionsButton(
            actions = state.content.actions,
        )
    }
}

@Composable
private fun NoItemsPlaceholder() {
    EmptyView(
        icon = {
            Icon(Icons.Outlined.SearchOff, null)
        },
        text = {
            Text(text = "No items")
        },
    )
}
