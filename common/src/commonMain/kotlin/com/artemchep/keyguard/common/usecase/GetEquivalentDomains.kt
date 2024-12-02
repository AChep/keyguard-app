package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DEquivalentDomains
import kotlinx.coroutines.flow.Flow

interface GetEquivalentDomains : () -> Flow<List<DEquivalentDomains>>
