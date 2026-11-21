package com.artemchep.keyguard.ui.toolbar.util

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform

object ToolbarBehavior {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun behavior(
        state: TopAppBarState = rememberTopAppBarState(),
    ): TopAppBarScrollBehavior {
        if (CurrentPlatform is Platform.Desktop) {
            return TopAppBarDefaults.pinnedScrollBehavior(
                state = state,
            )
        }
        return TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
            state = state,
        )
    }
}
