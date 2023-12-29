package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DGeneratorHistory
import kotlinx.coroutines.flow.Flow

interface GetGeneratorHistory : () -> Flow<List<DGeneratorHistory>>
