package com.artemchep.keyguard.feature.home.vault.apple

import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DTag
import com.artemchep.keyguard.feature.home.vault.search.benchmark.BenchmarkCorpusSize
import com.artemchep.keyguard.feature.home.vault.search.benchmark.VaultSearchBenchmarkFixtures
import com.artemchep.keyguard.feature.home.vault.search.engine.VaultSearchIndexMetadata
import com.artemchep.keyguard.ui.icons.generateAccentColors
import kotlinx.coroutines.flow.MutableStateFlow

internal data class VaultListFixtures(
    val ciphers: List<DSecret>,
    val accounts: List<DAccount>,
    val profiles: List<DProfile>,
    val folders: List<DFolder>,
    val tags: List<DTag>,
    val collections: List<DCollection>,
    val organizations: List<DOrganization>,
) {
    companion object {
        fun benchmarkSlice(
            itemCount: Int = 30,
        ): VaultListFixtures {
            val corpus = VaultSearchBenchmarkFixtures.buildCorpora()
                .getValue(BenchmarkCorpusSize.Small)
            return of(
                ciphers = corpus.items.take(itemCount),
                metadata = corpus.metadata,
            )
        }

        fun of(
            ciphers: List<DSecret>,
            metadata: VaultSearchIndexMetadata,
        ): VaultListFixtures = VaultListFixtures(
            ciphers = ciphers,
            accounts = metadata.accounts,
            profiles = metadata.accounts.map(::profileOf),
            folders = metadata.folders,
            tags = metadata.tags,
            collections = metadata.collections,
            organizations = metadata.organizations,
        )

        private fun profileOf(
            account: DAccount,
        ): DProfile = DProfile(
            accountId = account.accountId(),
            profileId = "profile-${account.accountId()}",
            keyBase64 = "",
            privateKeyBase64 = "",
            accountHost = account.host,
            email = "${account.username.orEmpty()}@${account.host}",
            emailVerified = true,
            accentColor = generateAccentColors(account.username.orEmpty()),
            name = account.username.orEmpty(),
            description = "",
            premium = true,
            hidden = false,
            securityStamp = null,
            twoFactorEnabled = null,
            masterPasswordHint = null,
            masterPasswordHintEnabled = null,
            unofficialServer = false,
            serverVersion = null,
        )
    }
}

internal class VaultListFixtureFlows(
    fixtures: VaultListFixtures,
) {
    val ciphers = MutableStateFlow(fixtures.ciphers)
    val accounts = MutableStateFlow(fixtures.accounts)
    val profiles = MutableStateFlow(fixtures.profiles)
    val folders = MutableStateFlow(fixtures.folders)
    val tags = MutableStateFlow(fixtures.tags)
    val collections = MutableStateFlow(fixtures.collections)
    val organizations = MutableStateFlow(fixtures.organizations)

    val canWrite = MutableStateFlow(true)
    val concealFields = MutableStateFlow(false)
    val appIcons = MutableStateFlow(false)
    val websiteIcons = MutableStateFlow(false)
}
