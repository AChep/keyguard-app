package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.SshAgentFilter
import kotlinx.coroutines.flow.Flow

interface GetSshAgentFilter : () -> Flow<SshAgentFilter>

