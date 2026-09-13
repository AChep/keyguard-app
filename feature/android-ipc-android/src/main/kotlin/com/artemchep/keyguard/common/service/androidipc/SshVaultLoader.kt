package com.artemchep.keyguard.common.service.androidipc

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.filterCiphers
import com.artemchep.keyguard.common.service.sshagent.isEligibleForSshAgent
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.kodein.di.direct
import org.kodein.di.instance

internal data class SshVault(
    val session: MasterSession.Key,
    val keys: List<DSecret>,
)

/**
 * Loads the SSH keys available over Android IPC from the unlocked
 * vault, or null while the vault is locked.
 */
internal class SshVaultLoader(
    private val getVaultSession: GetVaultSession,
    private val getSshAgentFilter: GetSshAgentFilter,
) {
    suspend fun load(): SshVault? = withContext(Dispatchers.IO) {
        val session = getVaultSession.valueOrNull as? MasterSession.Key
            ?: return@withContext null
        val getCiphers = session.di.direct.instance<GetCiphers>()
        val eligible = getCiphers()
            .first()
            .filter(DSecret::isEligibleForSshAgent)
        val keys = getSshAgentFilter()
            .first()
            .filterCiphers(
                directDI = session.di.direct,
                ciphers = eligible,
            )
        SshVault(
            session = session,
            keys = keys,
        )
    }
}
