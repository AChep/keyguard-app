package com.artemchep.keyguard.feature.license

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.service.license.model.License

@Immutable
data class LicenseState(
    val content: Content,
) {
    @Immutable
    data class Content(
        val items: List<License>,
    )
}
