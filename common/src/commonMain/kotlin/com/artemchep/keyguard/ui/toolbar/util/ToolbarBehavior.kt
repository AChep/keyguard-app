package com.artemchep.keyguard.ui.toolbar.util

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform

object ToolbarBehavior {
    @Composable
    fun behavior(): TopAppBarScrollBehavior {
        if (CurrentPlatform is Platform.Desktop) {
            return TopAppBarDefaults.pinnedScrollBehavior()
        }
        return TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    }
}
