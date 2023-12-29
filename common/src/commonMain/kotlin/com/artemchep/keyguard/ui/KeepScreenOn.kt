package com.artemchep.keyguard.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.artemchep.keyguard.common.usecase.GetKeepScreenOn
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun OptionallyKeepScreenOnEffect() {
    val shouldKeepScreenOn by run {
        val di = localDI()
        val get = remember(di) {
            di.direct.instance<GetKeepScreenOn>()
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
