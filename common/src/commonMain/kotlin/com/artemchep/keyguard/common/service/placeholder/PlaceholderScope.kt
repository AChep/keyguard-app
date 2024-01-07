package com.artemchep.keyguard.common.service.placeholder

import com.artemchep.keyguard.common.model.DSecret
import kotlinx.datetime.Instant

data class PlaceholderScope(
    val now: Instant,
    val cipher: DSecret,
    val url: String? = null,
)
