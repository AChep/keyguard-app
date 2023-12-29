package com.artemchep.keyguard.feature.auth.common

import androidx.compose.runtime.Immutable
import arrow.optics.optics

@Immutable
@optics
data class IntFieldModel(
    val number: Long,
    val min: Long,
    val max: Long,
    val onChange: ((Long) -> Unit)? = null,
) {
    companion object
}
