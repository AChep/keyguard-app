package com.artemchep.keyguard.common.service.filter

import com.artemchep.keyguard.common.model.DCipherFilter
import kotlinx.coroutines.flow.Flow

interface GetCipherFilters : () -> Flow<List<DCipherFilter>>
