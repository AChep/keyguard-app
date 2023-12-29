package com.artemchep.keyguard.platform

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

actual class LeContext(
    val context: Context,
)

actual val LocalLeContext: LeContext
    @Composable
    get() {
        val context = LocalContext.current
        return LeContext(context)
    }
