package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable

@Stable
interface Route {
    @Composable
    fun Content()
}

@Stable
interface DialogRoute : Route

interface SerializableRoute {
    val name: String
}
