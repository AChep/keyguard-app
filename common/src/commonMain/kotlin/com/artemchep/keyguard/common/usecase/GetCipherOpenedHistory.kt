package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.CipherOpenedHistoryMode
import com.artemchep.keyguard.common.model.DCipherOpenedHistory
import kotlinx.coroutines.flow.Flow

interface GetCipherOpenedHistory : (CipherOpenedHistoryMode) -> Flow<List<DCipherOpenedHistory>>
