package com.artemchep.keyguard.android.autofill

import com.artemchep.keyguard.android.autofill.v2.model.NormalizedStructureV2
import com.artemchep.keyguard.android.autofill.v2.model.ParseResultV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutofillStructureV2AdapterTest {
    @Test
    fun `known browsers preserve loopback origins`() {
        assertOrigins(
            applicationIds = listOf("org.mozilla.firefox", "com.android.chrome"),
            domains = loopbackDomains,
            preserved = true,
        )
    }

    @Test
    fun `embedded apps and unknown requesters omit loopback origins`() {
        assertOrigins(
            applicationIds = listOf("com.example.app", null),
            domains = loopbackDomains,
            preserved = false,
        )
    }

    @Test
    fun `public and LAN origins are preserved for all requesters`() {
        assertOrigins(
            applicationIds = listOf("org.mozilla.firefox", "com.example.app", null),
            domains = listOf(
                "example.com",
                "localhost.example.com",
                "127.0.0.1.example.com",
                "192.168.1.1",
                "10.0.0.1",
                "128.0.0.1",
                "[::2]",
                "[fd00::1]",
            ),
            preserved = true,
        )
    }

    @Test
    fun `malformed hosts do not throw or count as loopback addresses`() {
        assertOrigins(
            applicationIds = listOf("com.example.app"),
            domains = listOf(
                "127.0.0.999",
                "127.0.0.1.2",
                "127.-0.0.1",
                "127.+1.0.1",
                "[::1",
                "localhost/path",
            ),
            preserved = true,
        )
    }

    @Test
    fun `missing domain does not discard the scheme or web view marker`() {
        val result = ParseResultV2(
            structure = NormalizedStructureV2(
                webScheme = "https",
                webView = true,
            ),
        ).toAutofillStructure2()

        assertNull(result.webDomain)
        assertEquals("https", result.webScheme)
        assertEquals(true, result.webView)
    }

    @Test
    fun `native requests keep their application identity without a web origin`() {
        val result = ParseResultV2(
            structure = NormalizedStructureV2(
                applicationId = "com.example.app",
            ),
        ).toAutofillStructure2()

        assertEquals("com.example.app", result.applicationId)
        assertNull(result.webDomain)
        assertNull(result.webScheme)
        assertEquals(false, result.webView)
    }

    private fun assertOrigins(
        applicationIds: List<String?>,
        domains: List<String>,
        preserved: Boolean,
    ) {
        applicationIds.forEach { applicationId ->
            domains.forEach { domain ->
                val result = adapt(applicationId, domain)

                assertEquals(applicationId, result.applicationId, domain)
                assertEquals(domain.takeIf { preserved }, result.webDomain, domain)
                assertEquals("http".takeIf { preserved }, result.webScheme, domain)
                assertEquals(true, result.webView, domain)
            }
        }
    }

    private fun adapt(applicationId: String?, domain: String) = ParseResultV2(
        structure = NormalizedStructureV2(
            applicationId = applicationId,
            webDomain = domain,
            webScheme = "http",
            webView = true,
        ),
    ).toAutofillStructure2()

    private val loopbackDomains = listOf(
        "localhost",
        "LOCALHOST.",
        "127.0.0.1",
        "127.0.0.2",
        "127.255.255.255",
        "127.0.0.1.",
        "::1",
        "[::1]",
        "0:0:0:0:0:0:0:1",
        "[0000:0000:0000:0000:0000:0000:0000:0001]",
        "[::ffff:127.0.0.1]",
    )
}
