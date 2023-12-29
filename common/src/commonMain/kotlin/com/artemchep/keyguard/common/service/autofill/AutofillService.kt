package com.artemchep.keyguard.common.service.autofill

import kotlinx.coroutines.flow.Flow

interface AutofillService {
    fun status(): Flow<AutofillServiceStatus>
}
