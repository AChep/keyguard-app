package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow

interface GetMarkdown : () -> Flow<Boolean>
