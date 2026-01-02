package com.artemchep.keyguard.common.model

data class AutofillTarget(
    val username: String? = null,
    val links: List<LinkInfoPlatform> = emptyList(),
    val hints: List<AutofillHint> = emptyList(),
    val maxCount: Int = -1,
)
