package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DEquivalentDomains
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.testCipherFilterContext
import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.usecase.GetAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.GetEquivalentDomains
import com.artemchep.keyguard.feature.home.vault.search.TEST_INSTANT
import com.artemchep.keyguard.feature.home.vault.search.createSecret
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUrlBroadCheckImpl
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchtowerBroadUrisTest {
    @Test
    fun `archived and trashed host matches do not make an active domain broad`() = runTest {
        val getDefaultMatch = object : GetAutofillDefaultMatchDetection {
            override fun invoke() = flowOf(DSecret.Uri.MatchType.Domain)
        }
        val domains = EquivalentDomainsBuilderFactory(
            logRepository = LogRepositoryBridge(emptyList()),
            getEquivalentDomains = object : GetEquivalentDomains {
                override fun invoke() = flowOf(emptyList<DEquivalentDomains>())
            },
        )
        val check = CipherUrlBroadCheckImpl(object : TldService {
            override val version = "test"
            override fun getDomainName(host: String): IO<String> = { "example.com" }
        })
        val processor = WatchtowerBroadUris(getDefaultMatch, check, domains)
        val context = testCipherFilterContext(
            getAutofillDefaultMatchDetection = getDefaultMatch,
            cipherUrlBroadCheck = check,
            equivalentDomainsBuilderFactory = domains,
        )
        val domain = createSecret("domain").copy(
            uris = listOf(DSecret.Uri("example.com", DSecret.Uri.MatchType.Domain)),
        )
        val host = createSecret("host").copy(
            uris = listOf(DSecret.Uri("login.example.com", DSecret.Uri.MatchType.Host)),
        )

        suspend fun assertFindings(ciphers: List<DSecret>, expectedIds: List<String>) {
            assertEquals(
                expectedIds,
                processor.processActiveCiphers(ciphers).filter { it.threat }.map { it.cipher.id },
            )
            assertEquals(expectedIds.size, DFilter.ByBroadWebsites.count(context, ciphers))
            assertEquals(
                expectedIds,
                ciphers.filter(DFilter.ByBroadWebsites.prepare(context, ciphers)).map { it.id },
            )
        }

        assertFindings(listOf(domain, host), listOf(domain.id))
        assertFindings(listOf(domain, host.copy(archivedDate = TEST_INSTANT)), emptyList())
        assertFindings(listOf(domain, host.copy(deletedDate = TEST_INSTANT)), emptyList())
        assertFindings(listOf(domain.copy(archivedDate = TEST_INSTANT), host), emptyList())
        assertFindings(listOf(domain, host), listOf(domain.id))
    }
}
