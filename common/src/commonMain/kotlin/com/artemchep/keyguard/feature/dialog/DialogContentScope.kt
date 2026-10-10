package com.artemchep.keyguard.feature.dialog

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.Dp

/**
 * Content alignment and available viewport bounds before scrolling.
 *
 * The bounds exclude fixed dialog chrome and spacing. Short content may occupy less space;
 * an unbounded parent reports [Dp.Infinity] on the corresponding axis.
 */
@Stable
interface DialogContentScope : BoxScope {
    val viewportMaxWidth: Dp
    val viewportMaxHeight: Dp
}
