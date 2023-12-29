package com.artemchep.keyguard.platform

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable

actual val WindowInsets.Companion.leIme: WindowInsets
    @Composable
    @NonRestartableComposable
    get() = ime

actual val WindowInsets.Companion.leNavigationBars: WindowInsets
    @Composable
    @NonRestartableComposable
    get() = navigationBars

actual val WindowInsets.Companion.leStatusBars: WindowInsets
    @Composable
    @NonRestartableComposable
    get() = statusBars

actual val WindowInsets.Companion.leSystemBars: WindowInsets
    @Composable
    @NonRestartableComposable
    get() = systemBars

actual val WindowInsets.Companion.leDisplayCutout: WindowInsets
    @Composable
    @NonRestartableComposable
    get() = displayCutout
