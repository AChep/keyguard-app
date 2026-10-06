package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.AutotypeSpeed
import kotlinx.coroutines.flow.Flow

interface GetAutotypeSpeed : () -> Flow<AutotypeSpeed>
