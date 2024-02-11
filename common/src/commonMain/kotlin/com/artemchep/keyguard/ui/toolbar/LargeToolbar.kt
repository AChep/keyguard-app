package com.artemchep.keyguard.ui.toolbar

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.ui.toolbar.util.ToolbarColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LargeToolbar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    if (CurrentPlatform is Platform.Desktop) {
        SmallToolbar(
            title = title,
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = actions,
            scrollBehavior = scrollBehavior,
        )
        return
    }

    val containerColor = ToolbarColors.containerColor()
    val scrolledContainerColor = ToolbarColors.scrolledContainerColor(containerColor)
    LargeTopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = navigationIcon,
        colors = TopAppBarDefaults.largeTopAppBarColors()
            .copy(
                containerColor = containerColor,
                scrolledContainerColor = scrolledContainerColor,
            ),
        actions = actions,
        scrollBehavior = scrollBehavior,
    )
}
