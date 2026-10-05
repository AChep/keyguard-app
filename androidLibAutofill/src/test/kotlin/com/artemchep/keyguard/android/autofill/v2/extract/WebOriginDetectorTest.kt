package com.artemchep.keyguard.android.autofill.v2.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebOriginDetectorTest {
    @Test
    fun `blank domain and scheme do not produce a web origin`() {
        val blankValues = listOf(null, "", " \t")
        blankValues.forEach { domain ->
            blankValues.forEach { scheme ->
                assertNull(
                    WebOriginDetector.webOriginInfo(
                        className = "android.webkit.WebView",
                        webDomain = domain,
                        webScheme = scheme,
                    ),
                )
            }
        }
    }

    @Test
    fun `blank domain is normalized without discarding a valid scheme`() {
        val origin = WebOriginDetector.webOriginInfo(
            className = "android.webkit.WebView",
            webDomain = " \t",
            webScheme = "https",
        )

        assertNull(origin?.webDomain)
        assertEquals("https", origin?.webScheme)
    }
}
