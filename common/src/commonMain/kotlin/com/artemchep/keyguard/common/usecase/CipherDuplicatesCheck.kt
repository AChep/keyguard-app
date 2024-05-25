package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DSecretDuplicateGroup
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.StringResource
import kotlinx.serialization.Serializable

interface CipherDuplicatesCheck :
        (List<DSecret>, CipherDuplicatesCheck.Sensitivity) -> List<DSecretDuplicateGroup> {
    @Serializable
    enum class Sensitivity(
        val title: StringResource,
        val threshold: Float,
    ) {
        MAX(
            title = Res.string.tolerance_min,
            threshold = 0.3f,
        ),
        HIGH(
            title = Res.string.tolerance_low,
            threshold = 0.15f,
        ),
        NORMAL(
            title = Res.string.tolerance_normal,
            threshold = 0f,
        ),
        LOW(
            title = Res.string.tolerance_high,
            threshold = -0.1f,
        ),
        MIN(
            title = Res.string.tolerance_max,
            threshold = -0.15f,
        ),
    }
}
