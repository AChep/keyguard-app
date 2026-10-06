package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.feature.navigation.NavigationIntent
import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationIntentUrlsTest {
    @Test
    fun `mailto keeps unreserved characters and percent-encodes the rest`() {
        val intent = NavigationIntent.NavigateToEmail(
            email = "support@example.com",
            subject = "Hi & bye + 1",
            body = "Café ~_.-",
        )
        assertEquals(
            "mailto:support@example.com?subject=Hi%20%26%20bye%20%2B%201&body=Caf%C3%A9%20~_.-",
            intent.toMailtoUrl(),
        )
    }

    @Test
    fun `mailto percent-encodes the address so it cannot add headers`() {
        val intent = NavigationIntent.NavigateToEmail(
            email = "a+b@example.com?bcc=evil@example.org#x",
            subject = "Hi",
        )
        assertEquals(
            "mailto:a%2Bb@example.com%3Fbcc%3Devil@example.org%23x?subject=Hi",
            intent.toMailtoUrl(),
        )
    }

    @Test
    fun `mailto without subject or body has no query`() {
        val intent = NavigationIntent.NavigateToEmail(email = "support@example.com")
        assertEquals("mailto:support@example.com", intent.toMailtoUrl())
    }

    @Test
    fun `maps joins the address parts and percent-encodes them`() {
        val intent = NavigationIntent.NavigateToMaps(
            address1 = "1 Main St",
            address2 = " ",
            city = "Köln",
        )
        assertEquals(
            "https://maps.apple.com/?q=1%20Main%20St%2C%20K%C3%B6ln",
            intent.toMapsUrl(),
        )
    }
}
