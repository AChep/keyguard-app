package com.artemchep.keyguard.feature.send.view

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.component.VaultItemIcon2
import com.artemchep.keyguard.feature.home.vault.component.VaultViewItem
import com.artemchep.keyguard.feature.home.vault.component.rememberSecretAccentColor
import com.artemchep.keyguard.feature.home.vault.component.surfaceColorAtElevationSemi
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.Placeholder
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.SmallFab
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.icons.OfflineIcon
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar

@Composable
fun SendViewScreen(
    sendId: String,
    accountId: String,
) {
    val state = sendViewScreenState(
        contentColor = LocalContentColor.current,
        disabledContentColor = LocalContentColor.current
            .combineAlpha(MediumEmphasisAlpha),
        sendId = sendId,
        accountId = accountId,
    )
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
            val fabVisible = (state.content as? SendViewState.Content.Cipher)
                ?.onCopy != null || true // TODO: Why would I need it?
            val fabState = if (fabVisible) {
                FabState(
                    onClick = (state.content as? SendViewState.Content.Cipher)
                        ?.onCopy,
                    model = null,
                )
            } else {
                null
            }
            rememberUpdatedState(newValue = fabState)
        },
        floatingActionButton = {
            val updatedOnShare by rememberUpdatedState(
                (state.content as? SendViewState.Content.Cipher)
                    ?.onShare,
            )
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SmallFab(
                    onClick = {
                        updatedOnShare?.invoke()
                    },
                    icon = {
                        IconBox(main = Icons.Outlined.Share)
                    },
                )
                DefaultFab(
                    icon = {
                        IconBox(main = Icons.Outlined.ContentCopy)
                    },
                    text = {
                        Text(
                            text = "Copy share",
                        )
                    },
                )
            }
        },
        listState = listState,
    ) {
        when (state.content) {
            is SendViewState.Content.Loading -> {
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

            is SendViewState.Content.Cipher -> {
                val list = state.content.items
                if (list.isEmpty()) {
                    item("header.empty") {
                        Placeholder()
                    }
                }
                items(
                    items = list,
                    key = { model -> model.id },
                ) { model ->
                    VaultViewItem(
                        modifier = Modifier
                            .animateItemPlacement(),
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
    state: SendViewState,
) = Row(
    verticalAlignment = Alignment.CenterVertically,
) {
    val isLoading = state.content is SendViewState.Content.Loading
    val shimmerColor = LocalContentColor.current
        .combineAlpha(DisabledEmphasisAlpha)

    val avatarBackground = when (state.content) {
        is SendViewState.Content.Loading -> shimmerColor
        is SendViewState.Content.NotFound -> shimmerColor
        is SendViewState.Content.Cipher -> {
            val avatarIcon = state.content.icon
            val avatarBackground = if (
                avatarIcon !is VaultItemIcon.VectorIcon &&
                avatarIcon !is VaultItemIcon.TextIcon
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
        val icon = SendViewState.content.cipher.icon.getOrNull(state)
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
        is SendViewState.Content.Loading -> {
            SkeletonText(
                modifier = Modifier
                    .fillMaxWidth(0.33f),
                emphasis = MediumEmphasisAlpha,
            )
            // Apparently a text has some sort of margins?
            Text("")
        }

        is SendViewState.Content.Cipher -> {
            val name = state.content.data.name
            Text(
                text = name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        is SendViewState.Content.NotFound -> {
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
    state: SendViewState,
) {
    if (state.content is SendViewState.Content.Cipher) {
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
        OptionsButton(
            actions = state.content.actions,
        )
    }
}
