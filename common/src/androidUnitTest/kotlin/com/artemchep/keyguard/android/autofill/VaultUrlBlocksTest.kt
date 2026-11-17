package com.artemchep.keyguard.android.autofill

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DEquivalentDomains
import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomains
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.MatchDetection
import com.artemchep.keyguard.common.model.UrlBlockScope
import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.common.usecase.BlockedUrlCheck
import com.artemchep.keyguard.common.usecase.GetEquivalentDomains
import com.artemchep.keyguard.common.usecase.GetUrlBlocks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class VaultUrlBlocksTest {
    private val exposed = rule("Exposed", uri = "example.com")

    /** A rule only the vault holds: it is not exposed. */
    private val hidden = rule("Hidden", uri = "shop.example", exposed = false)

    private val vault = VaultUrlBlocks(
        getUrlBlocks = object : GetUrlBlocks {
            override fun invoke(): Flow<List<DGlobalUrlBlock>> = flowOf(listOf(hidden))
        },
        equivalentDomainsBuilderFactory = EquivalentDomainsBuilderFactory(
            logRepository = LogRepositoryBridge(emptyList()),
            getEquivalentDomains = object : GetEquivalentDomains {
                override fun invoke(): Flow<List<DEquivalentDomains>> = flowOf(
                    listOf(
                        group(accountId = "a", "example.com", "example.net"),
                        group(accountId = "b", "shop.example", "shop.test"),
                    ),
                )
            },
        ),
    )

    @Test
    fun `the locked vault matches the exposed rules without equivalent domains`() = runTest {
        assertEquals(listOf(exposed), matching("https://example.com", vault = null))
        assertEquals(emptyList(), matching("https://example.net", vault = null))
        assertEquals(emptyList(), matching("https://shop.example", vault = null))
    }

    @Test
    fun `the unlocked vault adds its rules and the equivalent domains of all accounts`() = runTest {
        assertEquals(listOf(exposed), matching("https://example.net", vault = vault))
        assertEquals(listOf(hidden), matching("https://shop.test", vault = vault))
    }

    private suspend fun matching(
        url: String,
        vault: VaultUrlBlocks?,
    ): List<DGlobalUrlBlock> = HostCheck.matchingWithVault(
        urls = listOf(url),
        exposed = listOf(exposed),
        vault = vault,
        defaultMatchDetection = DSecret.Uri.MatchType.Domain,
    )

    /** Matches the host of a URL, or any host equivalent to the URI of the rule. */
    private object HostCheck : BlockedUrlCheck {
        override fun invoke(
            rule: DGlobalUrlBlock,
            url: String,
            defaultMatchDetection: DSecret.Uri.MatchType,
            equivalentDomains: EquivalentDomains,
        ): IO<Boolean> = {
            url.substringAfter("://") in equivalentDomains.findEqDomains(rule.uri)
        }
    }

    private fun rule(
        name: String,
        uri: String,
        exposed: Boolean = true,
    ) = DGlobalUrlBlock(
        name = name,
        description = "",
        uri = uri,
        mode = MatchDetection.Host,
        createdDate = Instant.DISTANT_PAST,
        enabled = true,
        exposed = exposed,
        scope = UrlBlockScope.SAVE,
    )

    private fun group(
        accountId: String,
        vararg domains: String,
    ) = DEquivalentDomains(
        id = "$accountId:${domains.first()}",
        accountId = accountId,
        global = false,
        excluded = false,
        domains = domains.toList(),
    )
}
