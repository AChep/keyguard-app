package com.artemchep.keyguard.ui.scrollbar

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform

/**
 * Scrollbar selection modes.
 */
enum class ScrollbarSelectionMode {
    /**
     * Enable selection in the whole scrollbar and thumb
     */
    Full,

    /**
     * Enable selection in the thumb
     */
    Thumb,

    /**
     * Disable selection
     */
    Disabled,
    ;

    companion object {
        @get:Composable
        val default: ScrollbarSelectionMode
            get() = when (CurrentPlatform) {
                is Platform.Desktop -> Full
                is Platform.Mobile -> Thumb
            }
    }
}
