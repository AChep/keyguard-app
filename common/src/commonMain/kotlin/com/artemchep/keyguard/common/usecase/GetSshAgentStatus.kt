package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.SshAgentStatus
import kotlinx.coroutines.flow.Flow

interface GetSshAgentStatus : () -> Flow<SshAgentStatus>
