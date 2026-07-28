package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.autofill.AutofillService
import com.artemchep.keyguard.common.service.autofill.AutofillServiceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

object AutofillServiceIos : AutofillService {
    override fun status(): Flow<AutofillServiceStatus> =
        flowOf(AutofillServiceStatus.Disabled(onEnable = null))
}
