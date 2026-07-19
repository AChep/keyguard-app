package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomains
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.service.twofa.TwoFaServiceInfo
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class WatchtowerInactiveTfaTest {
    @Test
    fun `direct catalog match skips equivalent-domain lookup`() = runTest {
        val tldService = RecordingTfaTldService("example.com")
        val service = service(
            name = "direct",
            domain = "login.example.com",
        )

        val result = with(tldService) {
            WatchtowerInactiveTfa.match(
                cipher = secret("https://login.example.com/path"),
                tfaLibrary = listOf(service),
                equivalentDomains = EquivalentDomains(emptyMap()),
            ).toList()
        }

        assertEquals(listOf(service), result)
        assertEquals(0, tldService.callCount)
    }

    @Test
    fun `direct non-totp entry blocks equivalent fallback`() = runTest {
        val tldService = RecordingTfaTldService("source.com")
        val direct = service(
            name = "direct",
            domain = "login.source.com",
            tfa = emptySet(),
        )
        val equivalent = service(
            name = "equivalent",
            domain = "login.target.com",
        )

        val result = with(tldService) {
            WatchtowerInactiveTfa.match(
                cipher = secret("https://login.source.com/path"),
                tfaLibrary = listOf(direct, equivalent),
                equivalentDomains = EquivalentDomains(
                    mapOf("source.com" to listOf("source.com", "target.com")),
                ),
            ).toList()
        }

        assertEquals(emptyList(), result)
        assertEquals(0, tldService.callCount)
    }

    @Test
    fun `equivalent domain is checked after direct miss`() = runTest {
        val tldService = RecordingTfaTldService("source.com")
        val equivalent = service(
            name = "equivalent",
            domain = "login.target.com",
        )

        val result = with(tldService) {
            WatchtowerInactiveTfa.match(
                cipher = secret("https://login.source.com/path"),
                tfaLibrary = listOf(equivalent),
                equivalentDomains = EquivalentDomains(
                    mapOf("source.com" to listOf("source.com", "target.com")),
                ),
            ).toList()
        }

        assertEquals(listOf(equivalent), result)
        assertEquals(1, tldService.callCount)
    }

    @Test
    fun `domain index only matches exact catalog domains`() {
        val parent = service(name = "parent", domain = "example.com")
        val exact = service(name = "exact", domain = "login.example.com")
        val index = TwoFaServiceDomainIndex(listOf(parent, exact))

        assertEquals(exact, index.findFirstMatchOrNull("login.example.com"))
        assertNull(index.findFirstMatchOrNull("other.example.com"))
    }

    private fun service(
        name: String,
        domain: String,
        tfa: Set<String> = setOf("totp"),
    ) = TwoFaServiceInfo(
        name = name,
        domain = domain,
        domains = setOf(domain),
        tfa = tfa,
    )

    private fun secret(
        vararg urls: String,
    ) = DSecret(
        id = "cipher",
        accountId = "account",
        folderId = null,
        organizationId = null,
        collectionIds = emptySet(),
        revisionDate = Instant.parse("2024-01-01T00:00:00Z"),
        createdDate = null,
        archivedDate = null,
        deletedDate = null,
        service = BitwardenService(),
        name = "Login",
        notes = "",
        favorite = false,
        reprompt = false,
        synced = true,
        uris = urls.map { url ->
            DSecret.Uri(
                uri = url,
                match = DSecret.Uri.MatchType.Domain,
            )
        },
        type = DSecret.Type.Login,
        login = DSecret.Login(),
    )
}

private class RecordingTfaTldService(
    private val domain: String,
) : TldService {
    override val version: String = "test"

    var callCount: Int = 0

    override fun getDomainName(host: String): IO<String> {
        callCount += 1
        return io(domain)
    }
}
