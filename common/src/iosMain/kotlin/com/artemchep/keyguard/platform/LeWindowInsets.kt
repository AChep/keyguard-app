package com.artemchep.keyguard.platform

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.ui.unit.dp

private val empty = WindowInsets(left = 0.dp)

actual val WindowInsets.Companion.leIme: WindowInsets
    @Composable
    @NonRestartableComposable
    get() = empty

actual val WindowInsets.Companion.leNavigationBars: WindowInsets
    @Composable
    @NonRestartableComposable
    get() = empty

actual val WindowInsets.Companion.leStatusBars: WindowInsets
    @Composable
    @NonRestartableComposable
    get() = empty

actual val WindowInsets.Companion.leSystemBars: WindowInsets
    @Composable
    @NonRestartableComposable
    get() = empty

actual val WindowInsets.Companion.leDisplayCutout: WindowInsets
    @Composable
    @NonRestartableComposable
    get() = empty
