package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow

interface GetInMemoryLogsEnabled : () -> Flow<Boolean> {
    /** Latest value */
    val value: Boolean
}
