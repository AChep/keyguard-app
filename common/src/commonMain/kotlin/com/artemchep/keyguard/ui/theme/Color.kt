package com.artemchep.keyguard.ui.theme

import androidx.compose.ui.graphics.Color

fun Color.combineAlpha(alpha: Float) = copy(alpha = this.alpha * alpha)
