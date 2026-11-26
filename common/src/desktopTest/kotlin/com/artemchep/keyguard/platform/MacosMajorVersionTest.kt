package com.artemchep.keyguard.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MacosMajorVersionTest {
    @Test
    fun `parses the major version of os version`() {
        assertEquals(27, parseMacosMajorVersion("27.2"))
        assertEquals(26, parseMacosMajorVersion("26"))
        assertEquals(10, parseMacosMajorVersion("10.16"))
    }

    @Test
    fun `returns null for unknown versions`() {
        assertNull(parseMacosMajorVersion("x"))
        assertNull(parseMacosMajorVersion(""))
        assertNull(parseMacosMajorVersion(null))
    }
}
