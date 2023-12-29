package com.artemchep.keyguard.common.service.autofill

import com.artemchep.keyguard.platform.LeActivity

sealed interface AutofillServiceStatus {
    data class Enabled(
        val onDisable: (() -> Unit)?,
    ) : AutofillServiceStatus

    data class Disabled(
        val onEnable: ((LeActivity) -> Unit)?,
    ) : AutofillServiceStatus
}
