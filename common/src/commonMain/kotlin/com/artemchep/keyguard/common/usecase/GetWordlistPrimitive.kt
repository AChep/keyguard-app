package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow

interface GetWordlistPrimitive : (Long) -> Flow<List<String>>
