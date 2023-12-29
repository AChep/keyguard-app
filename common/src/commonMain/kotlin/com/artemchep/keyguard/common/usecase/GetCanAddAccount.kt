package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow

interface GetCanAddAccount : () -> Flow<Boolean>
