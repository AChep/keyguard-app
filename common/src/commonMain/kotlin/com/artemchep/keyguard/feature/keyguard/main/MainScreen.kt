package com.artemchep.keyguard.feature.keyguard.main

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.feature.home.HomeScreen

@OptIn(kotlin.time.ExperimentalTime::class)
@Composable
fun MainScreen() {
    val navBarVisible = LocalAppMode.current is AppMode.Main
    HomeScreen(
        navBarVisible = navBarVisible,
    )
}
