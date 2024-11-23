package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DSecret
import kotlinx.coroutines.flow.Flow

interface GetAutofillDefaultMatchDetection : () -> Flow<DSecret.Uri.MatchType>
