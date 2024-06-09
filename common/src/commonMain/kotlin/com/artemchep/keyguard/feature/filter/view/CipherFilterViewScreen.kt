package com.artemchep.keyguard.feature.filter.view

import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.search.filter.FilterItems
import com.artemchep.keyguard.feature.search.filter.FilterScreen
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior

@Composable
fun CipherFilterViewFullScreen(
    args: CipherFilterViewDialogRoute.Args,
) {
    CipherFilterViewScreen(
        args = args,
    )
}

@Composable
fun CipherFilterViewScreen(
    args: CipherFilterViewDialogRoute.Args,
) {
    val loadableState = produceCipherFilterViewState(
        args = args,
    )

    val title = args.model.name
    val scrollBehavior = ToolbarBehavior.behavior()
    when (loadableState) {
        is Loadable.Ok -> {
            val state = loadableState.value
            CipherFilterViewScreenOk(
                title = title,
                scrollBehavior = scrollBehavior,
                state = state,
            )
        }

        is Loadable.Loading -> {
            CipherFilterViewSkeleton(
                title = title,
                scrollBehavior = scrollBehavior,
            )
        }
    }
}

@Composable
fun CipherFilterViewSkeleton(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = title,
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
    }
}

@Composable
fun CipherFilterViewScreenOk(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    state: CipherFilterViewState,
) {
    val content by state.toolbarFlow.collectAsState()
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = content.model?.name
                            ?: title,
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                actions = {
                    OptionsButton(
                        actions = content.actions,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        val filter by state.filterFlow.collectAsState(
            initial = CipherFilterViewState.Filter(),
        )
        FilterItems(
            items = filter.items,
        )
    }
}
