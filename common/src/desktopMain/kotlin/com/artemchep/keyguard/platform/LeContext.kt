package com.artemchep.keyguard.platform

import androidx.compose.runtime.Composable

actual class LeContext

actual val LocalLeContext: LeContext
    @Composable
    get() {
        return LeContext()
    }
