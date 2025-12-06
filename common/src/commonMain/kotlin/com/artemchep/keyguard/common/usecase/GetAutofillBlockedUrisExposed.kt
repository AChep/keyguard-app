package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import kotlinx.coroutines.flow.Flow

interface GetAutofillBlockedUrisExposed : () -> Flow<List<DGlobalUrlBlock>>
