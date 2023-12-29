package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.Subscription
import kotlinx.coroutines.flow.Flow

interface GetSubscriptions : () -> Flow<List<Subscription>?>
