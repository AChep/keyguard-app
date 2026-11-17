package com.artemchep.keyguard.android.autofill

import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomains
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.BlockedUrlCheck
import com.artemchep.keyguard.common.usecase.GetUrlBlocks
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.matching
import com.artemchep.keyguard.di.resolve
import kotlinx.coroutines.flow.first

/**
 * What the unlocked vault adds to a check of the blocked URIs: all of its rules, also the
 * ones that are not exposed, and the equivalent domains of all of its accounts, since an
 * offer to save a login belongs to no account.
 */
internal class VaultUrlBlocks(
    private val getUrlBlocks: GetUrlBlocks,
    private val equivalentDomainsBuilderFactory: EquivalentDomainsBuilderFactory,
) {
    suspend fun rules(): List<DGlobalUrlBlock> = getUrlBlocks().first()

    suspend fun equivalentDomains(): EquivalentDomains = equivalentDomainsBuilderFactory
        .build()
        .getAndCacheAll()
}

/**
 * Returns what the unlocked vault adds to a check of the blocked URIs, or `null` while the
 * vault is locked. Reads only the session, not the list of ciphers.
 */
internal suspend fun GetVaultSession.vaultUrlBlocksOrNull(): VaultUrlBlocks? =
    (invoke().first() as? MasterSession.Key)
        ?.session
        ?.resolve {
            VaultUrlBlocks(
                getUrlBlocks = get<GetUrlBlocks>(),
                equivalentDomainsBuilderFactory = get<EquivalentDomainsBuilderFactory>(),
            )
        }

/**
 * The enabled blocked URIs that match any of the [urls]: the [exposed] ones, and with the
 * [vault] unlocked, its own ones too. The equivalent domains count only while the vault is
 * unlocked: the locked vault has none.
 */
internal suspend fun BlockedUrlCheck.matchingWithVault(
    urls: Collection<String>,
    exposed: List<DGlobalUrlBlock>,
    vault: VaultUrlBlocks?,
    defaultMatchDetection: DSecret.Uri.MatchType,
): List<DGlobalUrlBlock> = matching(
    rules = exposed + vault?.rules().orEmpty(),
    urls = urls,
    defaultMatchDetection = defaultMatchDetection,
    equivalentDomains = vault?.equivalentDomains() ?: EquivalentDomains(domains = emptyMap()),
)
