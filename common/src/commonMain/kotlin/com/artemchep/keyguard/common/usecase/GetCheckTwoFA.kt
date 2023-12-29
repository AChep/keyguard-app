package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow

interface GetCheckTwoFA : () -> Flow<Boolean>
