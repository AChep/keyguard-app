package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DSecretDuplicateGroup
import kotlinx.serialization.Serializable

interface CipherDuplicatesCheck :
        (List<DSecret>, CipherDuplicatesCheck.Sensitivity) -> List<DSecretDuplicateGroup> {
    @Serializable
    enum class Sensitivity(
        val threshold: Float,
    ) {
        MAX(0.3f),
        HIGH(0.15f),
        NORMAL(0f),
        LOW(-0.1f),
        MIN(-0.15f),
    }
}
