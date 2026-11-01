package com.artemchep.keyguard.feature.dragout

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.dragout.model.DragOutCapabilities
import com.artemchep.keyguard.feature.dragout.model.draggableUrl
import com.artemchep.keyguard.platform.Platform
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DragOutCapabilitiesTest {
    @Test
    fun `each platform offers only the transports it has`() {
        val phone = DragOutCapabilities.of(Platform.Mobile.Android(isChromebook = false, isWatch = false, sdk = 36))
        assertTrue(phone.files && phone.urls)
        assertNull(phone.fileMaximumBytes)
        assertEquals(
            DragOutCapabilities.None,
            DragOutCapabilities.of(Platform.Mobile.Android(isChromebook = false, isWatch = true, sdk = 36)),
        )
        assertEquals(2L * 1024 * 1024, DragOutCapabilities.of(Platform.Mobile.Ios.Native).fileMaximumBytes)
        listOf(Platform.Desktop.MacOS.Jvm, Platform.Desktop.Windows).forEach { platform ->
            val capabilities = DragOutCapabilities.of(platform)
            assertTrue(capabilities.files && capabilities.urls)
        }
        listOf(Platform.Desktop.Linux.native, Platform.Desktop.Other).forEach { platform ->
            val capabilities = DragOutCapabilities.of(platform)
            assertFalse(capabilities.files)
            assertTrue(capabilities.urls)
        }
    }

    @Test
    fun `only plain web links are draggable`() {
        val desktop = DragOutCapabilities.of(Platform.Desktop.Linux.native)
        assertEquals("https://example.com/login", draggableUrl(Url("https://example.com/login"), null, desktop))
        assertEquals(
            "http://example.com/",
            draggableUrl(Url("http://example.com/"), DSecret.Uri.MatchType.StartsWith, desktop),
        )
        assertNull(draggableUrl(Url("https://example.com/"), DSecret.Uri.MatchType.RegularExpression, desktop))
        assertNull(draggableUrl(Url("https://user:secret@example.com/"), null, desktop))
        assertNull(draggableUrl(Url("https://user@example.com/"), null, desktop))
        assertNull(draggableUrl(Url("ftp://example.com/"), null, desktop))
        assertNull(draggableUrl(Url("https://example.com/"), null, DragOutCapabilities.None))
    }
}
