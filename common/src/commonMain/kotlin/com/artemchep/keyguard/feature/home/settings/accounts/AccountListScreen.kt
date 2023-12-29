package com.artemchep.keyguard.feature.home.settings.accounts

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.items
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.NoAccounts
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.auth.login.LoginRoute
import com.artemchep.keyguard.feature.home.settings.accounts.component.AccountListItem
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.icons.SyncIcon
import com.artemchep.keyguard.ui.items.FlatItemSkeleton
import com.artemchep.keyguard.ui.selection.SelectionBar
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun AccountListScreen() {
    val controller by rememberUpdatedState(LocalNavigationController.current)
    val r = registerRouteResultReceiver(LoginRoute()) {
        controller.queue(NavigationIntent.Pop)
    }

    val accountListStateWrapper = accountListScreenState()
    val accountListState = remember(accountListStateWrapper) {
        accountListStateWrapper.unwrap(
            onAddAccount = {
                controller.queue(NavigationIntent.NavigateToRoute(r))
            },
        )
    }
    AccountListScreenContent(
        state = accountListState,
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterialApi::class,
)
@Composable
fun AccountListScreenContent(
    state: AccountListState,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(stringResource(Res.strings.account_main_header_title))
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = run {
            val fabOnClick = state.onAddNewAccount
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
            DefaultFab(
                icon = {
                    Icon(Icons.Outlined.Add, null)
                },
                text = {
                    Text(stringResource(Res.strings.account_main_add_account_title))
                },
            )
        },
        bottomBar = {
            ExpandedIfNotEmpty(
                valueOrNull = state.selection,
            ) { selection ->
                SelectionBar(
                    title = {
                        val text = stringResource(Res.strings.selection_n_selected, selection.count)
                        Text(text)
                    },
                    trailing = {
                        val updatedOnSelectAll by rememberUpdatedState(selection.onSelectAll)
                        IconButton(
                            enabled = updatedOnSelectAll != null,
                            onClick = {
                                updatedOnSelectAll?.invoke()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.SelectAll,
                                contentDescription = null,
                            )
                        }
                        IconButton(
                            enabled = selection.onSync != null,
                            onClick = {
                                selection.onSync?.invoke()
                            },
                        ) {
                            SyncIcon(rotating = false)
                        }
                        OptionsButton(
                            actions = selection.actions,
                        )
                    },
                    onClear = selection.onClear,
                )
            }
        },
    ) {
        if (state.items.isEmpty()) {
            item("header") {
                if (state.isLoading) {
                    FlatItemSkeleton()
                } else {
                    EmptyView(
                        icon = {
                            Icon(Icons.Outlined.NoAccounts, null)
                        },
                        text = {
                            Text(
                                text = stringResource(Res.strings.accounts_empty_label),
                            )
                        },
                    )
                }
            }
        }
        items(
            items = state.items,
            key = { model -> model.id },
        ) { model ->
            AccountListItem(
                item = model,
            )
        }
    }
}
