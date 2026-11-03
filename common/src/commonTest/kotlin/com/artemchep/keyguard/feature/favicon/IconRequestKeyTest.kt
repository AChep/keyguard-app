package com.artemchep.keyguard.feature.favicon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IconRequestKeyTest {
    @Test
    fun `keys round trip`() {
        val requests = listOf(
            FaviconUrl(serverId = "account", url = "https://example.com:8443"),
            FaviconUrl(url = "https://example.com"),
            AppIconUrl(packageName = "com.example"),
            IosAppIconUrl(bundleId = "com.example"),
            RemoteIconUrl(url = "https://logos.example.com/x.png"),
        )

        requests.forEach { request ->
            assertEquals(request, iconRequestOrNull(request.toIconKey()))
        }
    }

    @Test
    fun `pages of a website have the same key`() {
        val keys = setOf(
            FaviconUrl(serverId = "account", url = "https://example.com/a|b\nc").toIconKey(),
            FaviconUrl(serverId = "account", url = "https://Example.com/login?next=/").toIconKey(),
            FaviconUrl(serverId = "account", url = "https://example.com").toIconKey(),
        )

        assertEquals(1, keys.size)
        assertEquals(
            FaviconUrl(serverId = "account", url = "https://example.com"),
            iconRequestOrNull(keys.single()),
        )
    }

    @Test
    fun `different requests have different keys`() {
        val keys = setOf(
            FaviconUrl(url = "https://example.com").toIconKey(),
            RemoteIconUrl(url = "https://example.com").toIconKey(),
            FaviconUrl(serverId = "a", url = "https://example.com").toIconKey(),
            AppIconUrl("com.example").toIconKey(),
            IosAppIconUrl("com.example").toIconKey(),
        )

        assertEquals(5, keys.size)
    }

    @Test
    fun `garbage is not a request`() {
        assertNull(iconRequestOrNull(""))
        assertNull(iconRequestOrNull("https://example.com"))
        assertNull(iconRequestOrNull("""{"type":"unknown"}"""))
    }
}
