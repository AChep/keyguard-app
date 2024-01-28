package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DSecretDuplicateGroup
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.StringResource
import kotlinx.serialization.Serializable

interface CipherDuplicatesCheck :
        (List<DSecret>, CipherDuplicatesCheck.Sensitivity) -> List<DSecretDuplicateGroup> {
    @Serializable
    enum class Sensitivity(
        val title: StringResource,
        val threshold: Float,
    ) {
        MAX(
            title = Res.strings.tolerance_min,
            threshold = 0.3f,
        ),
        HIGH(
            title = Res.strings.tolerance_low,
            threshold = 0.15f,
        ),
        NORMAL(
            title = Res.strings.tolerance_normal,
            threshold = 0f,
        ),
        LOW(
            title = Res.strings.tolerance_high,
            threshold = -0.1f,
        ),
        MIN(
            title = Res.strings.tolerance_max,
            threshold = -0.15f,
        ),
    }
}
