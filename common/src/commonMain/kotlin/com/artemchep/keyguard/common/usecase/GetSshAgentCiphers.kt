package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DSecret
import kotlinx.coroutines.flow.Flow

/** The items whose keys the SSH agent offers. */
interface GetSshAgentCiphers : () -> Flow<List<DSecret>>
