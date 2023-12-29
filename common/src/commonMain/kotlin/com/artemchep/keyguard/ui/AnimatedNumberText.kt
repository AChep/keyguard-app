package com.artemchep.keyguard.ui

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

@Composable
fun animatedNumberText(
    number: Int,
): String {
    val a by animateIntAsState(number)
    return a.toString()
}
