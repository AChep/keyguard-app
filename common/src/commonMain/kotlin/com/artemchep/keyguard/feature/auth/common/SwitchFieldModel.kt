package com.artemchep.keyguard.feature.auth.common

import androidx.compose.runtime.Immutable
import arrow.optics.optics

@Immutable
@optics
data class SwitchFieldModel(
    val checked: Boolean = false,
    val onChange: ((Boolean) -> Unit)? = null,
) {
    companion object
}
