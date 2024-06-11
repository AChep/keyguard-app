package com.artemchep.keyguard.common.model

data class AutofillTarget(
    val links: List<LinkInfoPlatform>,
    val hints: List<AutofillHint>,
    val maxCount: Int = -1,
)
