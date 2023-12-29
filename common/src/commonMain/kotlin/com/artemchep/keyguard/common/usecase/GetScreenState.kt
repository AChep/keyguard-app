package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow

interface GetScreenState : (String) -> Flow<Map<String, Any?>>
