package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.CipherFilterContext
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.filterCiphers
import com.artemchep.keyguard.common.service.sshagent.isEligibleForSshAgent
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetSshAgentCiphers
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlin.coroutines.CoroutineContext

class GetSshAgentCiphersImpl(
    filterContext: CipherFilterContext,
    getCiphers: GetCiphers,
    getSshAgentFilter: GetSshAgentFilter,
    windowCoroutineScope: WindowCoroutineScope,
    dispatcher: CoroutineContext = Dispatchers.Default,
) : GetSshAgentCiphers {
    // Shared, so that each reader doesn't filter the whole vault again.
    private val sharedFlow = combine(
        getCiphers(),
        getSshAgentFilter()
            .map { it.normalize() },
    ) { ciphers, filter ->
        val eligibleCiphers = ciphers
            .filter { it.isEligibleForSshAgent() }
        filter.filterCiphers(
            context = filterContext,
            ciphers = eligibleCiphers,
        )
    }
        .distinctUntilChanged()
        .flowOn(dispatcher)
        .shareIn(windowCoroutineScope, SharingStarted.WhileSubscribed(5000L), replay = 1)

    override fun invoke(): Flow<List<DSecret>> = sharedFlow
}
