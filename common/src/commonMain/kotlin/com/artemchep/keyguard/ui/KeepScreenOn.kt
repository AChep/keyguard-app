package com.artemchep.keyguard.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.artemchep.keyguard.common.usecase.GetKeepScreenOn
import org.koin.compose.currentKoinScope

@Composable
fun OptionallyKeepScreenOnEffect() {
    val shouldKeepScreenOn by run {
        val di = currentKoinScope()
        val get = remember(di) {
            di.get<GetKeepScreenOn>()
        }
        remember(get) {
            get()
        }.collectAsState(false)
    }
    if (shouldKeepScreenOn) {
        KeepScreenOnEffect()
    }
}

@Composable
expect fun KeepScreenOnEffect()
