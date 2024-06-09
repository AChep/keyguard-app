package com.artemchep.keyguard.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.home.vault.component.VaultViewItem
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.OptionallyKeepScreenOnEffect
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

@Composable
fun AccountViewScreen(
    accountId: AccountId,
) {
    OptionallyKeepScreenOnEffect()

    val state = accountState(accountId)
    AccountViewScreen(
        state = state,
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterialApi::class,
)
@Composable
fun AccountViewScreen(
    state: AccountViewState,
) {
    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    AccountViewTitle(state)
                },
                navigationIcon = {
                    NavigationIcon()
                },
                actions = {
                    val updatedOnOpenWebVault by rememberUpdatedState(
                        newValue = AccountViewState.content.data.onOpenWebVault.getOrNull(state),
                    )
                    TextButton(
                        enabled = updatedOnOpenWebVault != null,
                        onClick = {
                            updatedOnOpenWebVault?.invoke()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Launch,
                            contentDescription = null,
                        )
                        Spacer(
                            modifier = Modifier
                                .width(Dimens.buttonIconPadding),
                        )
                        Text(
                            text = stringResource(Res.string.web_vault),
                        )
                    }

                    val actions = AccountViewState.content.data.actions.getOrNull(state)
                        ?: return@LargeToolbar
                    OptionsButton(
                        actions = actions,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = run {
            val primaryAction = AccountViewState.content.data.primaryAction.getOrNull(state)
            val fabOnClick = primaryAction?.onClick
            val fabState = if (fabOnClick != null) {
                FabState(
                    onClick = fabOnClick,
                    model = null,
                )
            } else {
                null
            }
            rememberUpdatedState(newValue = fabState)
        },
        floatingActionButton = {
            val primaryAction = AccountViewState.content.data.primaryAction.getOrNull(state)
            DefaultFab(
                icon = {
                    val icon = primaryAction?.icon
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                        )
                    }
                },
                text = {
                    Text(primaryAction?.text.orEmpty())
                },
            )
        },
    ) {
        val items = AccountViewState.content.data.items.getOrNull(state).orEmpty()
        items(
            items,
            key = { it.id },
        ) {
            VaultViewItem(item = it)
        }
    }
}

@Composable
private fun AccountViewTitle(
    state: AccountViewState,
) = Column {
    val isLoading = state.content is AccountViewState.Content.Skeleton
    val shimmerColor = LocalContentColor.current
        .combineAlpha(DisabledEmphasisAlpha)

    val host by derivedStateOf {
        val data = AccountViewState.content.data.data.getOrNull(state)
        data?.host.orEmpty()
    }
    if (isLoading) {
        Box(
            modifier = Modifier
                .height(IntrinsicSize.Min),
        ) {
            Box(
                modifier = Modifier
                    .shimmer()
                    .fillMaxHeight()
                    .fillMaxWidth(0.28f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(shimmerColor),
            )
            // only needed to measure the text size
            Text(
                "",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .height(IntrinsicSize.Min),
        ) {
            Box(
                modifier = Modifier
                    .shimmer()
                    .fillMaxHeight()
                    .fillMaxWidth(0.45f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(shimmerColor),
            )
            // only needed to measure the text size
            Text(
                "",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    } else {
        Text(
            text = host,
            style = MaterialTheme.typography.labelSmall,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
            overflow = TextOverflow.Ellipsis,
            maxLines = 2,
        )
        Text(
            text = "Bitwarden",
            style = MaterialTheme.typography.titleMedium,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
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
