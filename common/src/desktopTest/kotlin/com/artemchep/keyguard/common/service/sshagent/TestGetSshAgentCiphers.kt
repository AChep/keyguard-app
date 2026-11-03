package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.SshAgentFilter
import com.artemchep.keyguard.common.model.testCipherFilterContext
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.usecase.impl.GetSshAgentCiphersImpl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope

/** The production filter of the agent's [ciphers], shared in the test's background scope. */
internal fun TestScope.testGetSshAgentCiphers(
    ciphers: Flow<List<DSecret>>,
    filter: Flow<SshAgentFilter>,
    dispatcher: CoroutineDispatcher,
) = GetSshAgentCiphersImpl(
    filterContext = testCipherFilterContext(),
    getCiphers = object : GetCiphers {
        override fun invoke(): Flow<List<DSecret>> = ciphers
    },
    getSshAgentFilter = object : GetSshAgentFilter {
        override fun invoke(): Flow<SshAgentFilter> = filter
    },
    windowCoroutineScope = object : WindowCoroutineScope, CoroutineScope by backgroundScope {},
    dispatcher = dispatcher,
)
