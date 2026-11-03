package com.artemchep.keyguard.feature.dragout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DragOutUriTest {
    private val authority = "com.artemchep.keyguard.test.dragout"
    private val token = "2c92a950-f205-45c1-a866-b886ca48dcf1"
    private val canonical = "content://$authority/content/$token"

    @Test
    fun `only the canonical generated URI identifies a capability`() {
        assertEquals(token, dragOutToken(canonical, authority))
    }

    @Test
    fun `encoded aliases queries fragments user identifiers and alternate paths are rejected`() {
        listOf(
            canonical.replace("content://", "file://"),
            canonical.replace(authority, "$authority.other"),
            canonical.replace(authority, "0@$authority"),
            canonical.replace("/content/", "/%63ontent/"),
            canonical.replace("/content/", "//content/"),
            canonical.replace("/content/", "/content/../content/"),
            "$canonical/",
            "$canonical?name=private.txt",
            "$canonical#fragment",
            canonical.replace(token, token.uppercase()),
            canonical.replace(token, "1-1-1-1-1"),
            canonical.replace(token, "%32" + token.drop(1)),
        ).forEach { uri -> assertNull(dragOutToken(uri, authority), uri) }
    }
}
