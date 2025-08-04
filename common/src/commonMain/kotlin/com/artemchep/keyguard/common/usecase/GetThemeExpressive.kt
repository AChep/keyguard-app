package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow

interface GetThemeExpressive : () -> Flow<Boolean>
