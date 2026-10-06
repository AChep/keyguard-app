package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.model.EquivalentDomains
import com.artemchep.keyguard.common.service.passkey.PassKeyServiceInfo
import com.artemchep.keyguard.common.service.tld.TldService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchtowerInactivePasskeyTest {
    @Test
    fun `direct catalog match skips equivalent-domain lookup`() = runTest {
        val tldService = RecordingTldService("example.com")
        val service = service(
            id = "direct",
            domain = "login.example.com",
        )

        val result = with(tldService) {
            WatchtowerInactivePasskey.match(
                cipher = domainLoginSecret("https://login.example.com/path"),
                passkeyLibrary = listOf(service),
                equivalentDomains = EquivalentDomains(emptyMap()),
            ).toList()
        }

        assertEquals(listOf(service), result)
        assertEquals(0, tldService.callCount)
    }

    @Test
    fun `direct non-signin entry blocks equivalent fallback`() = runTest {
        val tldService = RecordingTldService("source.com")
        val direct = service(
            id = "direct",
            domain = "login.source.com",
            features = emptySet(),
        )
        val equivalent = service(
            id = "equivalent",
            domain = "login.target.com",
        )

        val result = with(tldService) {
            WatchtowerInactivePasskey.match(
                cipher = domainLoginSecret("https://login.source.com/path"),
                passkeyLibrary = listOf(direct, equivalent),
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
        val tldService = RecordingTldService("source.com")
        val equivalent = service(
            id = "equivalent",
            domain = "login.target.com",
        )

        val result = with(tldService) {
            WatchtowerInactivePasskey.match(
                cipher = domainLoginSecret("https://login.source.com/path"),
                passkeyLibrary = listOf(equivalent),
                equivalentDomains = EquivalentDomains(
                    mapOf("source.com" to listOf("source.com", "target.com")),
                ),
            ).toList()
        }

        assertEquals(listOf(equivalent), result)
        assertEquals(1, tldService.callCount)
    }

    private fun service(
        id: String,
        domain: String,
        features: Set<String> = setOf("signin"),
    ) = PassKeyServiceInfo(
        id = id,
        name = id,
        domain = domain,
        domains = setOf(domain),
        features = features,
    )
}

private class RecordingTldService(
    private val domain: String,
) : TldService {
    override val version: String = "test"

    var callCount: Int = 0

    override fun getDomainName(host: String): IO<String> {
        callCount += 1
        return io(domain)
    }
}
