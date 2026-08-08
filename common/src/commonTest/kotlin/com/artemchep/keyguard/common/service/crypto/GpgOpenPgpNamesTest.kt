package com.artemchep.keyguard.common.service.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GpgOpenPgpNamesTest {
    @Test
    fun `normalizes bracketed and bare email addresses`() {
        assertEquals(
            "alice@example.com",
            normalizeGpgUserIdEmail(" Alice Example <ALICE@Example.COM> "),
        )
        assertEquals(
            "alice@example.com",
            normalizeGpgUserIdEmail("  ALICE@example.com "),
        )
        assertEquals("a@b", normalizeGpgUserIdEmail("Name < a@b >"))
        assertEquals(
            "müller@例子.测试",
            normalizeGpgUserIdEmail("MÜLLER@例子.测试"),
        )
    }

    @Test
    fun `uses the first bracketed email address`() {
        assertEquals(
            "first@example.com",
            normalizeGpgUserIdEmail(
                "Name <first@example.com> <second@example.com>",
            ),
        )
        assertEquals(
            "a@b",
            normalizeGpgUserIdEmail("Name <a@b> trailing@example.com"),
        )
    }

    @Test
    fun `preserves nested bracket compatibility`() {
        assertEquals(
            "good@example.com",
            normalizeGpgUserIdEmail("<bad<good@example.com>"),
        )
    }

    @Test
    fun `rejects invalid email addresses`() {
        listOf(
            "",
            "Alice Example",
            "Name <not-email>",
            "a@@b",
            "a@\tb",
            "a@",
            "@b",
        ).forEach { value ->
            assertNull(normalizeGpgUserIdEmail(value), value)
        }
    }
}
