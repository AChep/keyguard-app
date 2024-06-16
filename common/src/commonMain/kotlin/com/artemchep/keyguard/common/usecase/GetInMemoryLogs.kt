package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.Log
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.Flow

interface GetInMemoryLogs : () -> Flow<ImmutableList<Log>>
