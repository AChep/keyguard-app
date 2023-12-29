package com.artemchep.keyguard.common.model

import androidx.compose.ui.graphics.painter.Painter

/**
 * @author Artem Chepurnyi
 */
sealed interface LinkInfoLaunch : LinkInfo {
    data class Allow(
        val apps: List<AppInfo>,
    ) : LinkInfoLaunch {
        data class AppInfo(
            val label: String,
            val icon: Painter? = null,
        )
    }

    data object Deny : LinkInfoLaunch
}
