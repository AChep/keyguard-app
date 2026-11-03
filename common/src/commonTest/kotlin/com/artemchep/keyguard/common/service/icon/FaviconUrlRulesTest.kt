package com.artemchep.keyguard.common.service.icon

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FaviconUrlRulesTest {
    @Test
    fun `origin drops the path, query and user info`() {
        assertEquals(
            Url("https://example.com"),
            faviconOrigin("https://user:pass@Example.COM/login?next=/#top"),
        )
        assertEquals(
            Url("http://example.com:8080"),
            faviconOrigin("http://example.com:8080/a/b"),
        )
    }

    @Test
    fun `origin needs a http website`() {
        assertNull(faviconOrigin("ftp://example.com"))
        assertNull(faviconOrigin("androidapp://com.example"))
        assertNull(faviconOrigin("https://"))
        assertNull(faviconOrigin("::"))
    }

    @Test
    fun `icon server gets the host of a public website`() {
        assertEquals("example.com", iconServerLookupHost(Url("https://example.com/login")))
        assertEquals("example.com", iconServerLookupHost(Url("https://example.com:443/")))
        assertEquals("example.com", iconServerLookupHost(Url("http://example.com:80/")))
    }

    @Test
    fun `icon server does not get local or non-default port websites`() {
        assertNull(iconServerLookupHost(Url("https://example.com:8443/")))
        assertNull(iconServerLookupHost(Url("http://localhost/")))
        assertNull(iconServerLookupHost(Url("http://10.0.0.1/")))
        assertNull(iconServerLookupHost(Url("http://172.16.0.1/")))
        assertNull(iconServerLookupHost(Url("http://192.168.1.1/")))
    }

    @Test
    fun `icon urls must be http`() {
        assertTrue(isFetchableIconUrl(Url("https://example.com/icon.png"), siteIsLocal = false))
        assertFalse(isFetchableIconUrl(Url("ftp://example.com/icon.png"), siteIsLocal = false))
        assertFalse(isFetchableIconUrl(Url("http://192.168.1.1/icon.png"), siteIsLocal = false))
        assertTrue(isFetchableIconUrl(Url("http://192.168.1.1/icon.png"), siteIsLocal = true))
    }
}
