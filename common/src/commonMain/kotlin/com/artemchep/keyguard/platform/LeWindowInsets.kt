package com.artemchep.keyguard.platform

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable

@get:Composable
expect val WindowInsets.Companion.leIme: WindowInsets

@get:Composable
expect val WindowInsets.Companion.leNavigationBars: WindowInsets

@get:Composable
expect val WindowInsets.Companion.leStatusBars: WindowInsets

@get:Composable
expect val WindowInsets.Companion.leSystemBars: WindowInsets

@get:Composable
expect val WindowInsets.Companion.leDisplayCutout: WindowInsets
