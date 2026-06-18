package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DSshUsageHistory
import com.artemchep.keyguard.common.model.SshUsageHistoryMode
import kotlinx.coroutines.flow.Flow

interface GetSshUsageHistory : (SshUsageHistoryMode) -> Flow<List<DSshUsageHistory>>
