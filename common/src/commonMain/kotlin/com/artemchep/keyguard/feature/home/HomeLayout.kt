package com.artemchep.keyguard.feature.home

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

internal val LocalHomeLayout = staticCompositionLocalOf<HomeLayout> {
    throw IllegalStateException("Home layout must be initialized!")
}

sealed interface HomeLayout {
    data object Vertical : HomeLayout
    data object Horizontal : HomeLayout
}

@Composable
fun ResponsiveLayout(
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) = BoxWithConstraints(
    modifier = Modifier
        .fillMaxSize(),
) {
    val layout = when {
        maxHeight < maxWidth -> HomeLayout.Horizontal
        else -> HomeLayout.Vertical
    }
    CompositionLocalProvider(LocalHomeLayout provides layout) {
        content()
    }
}
