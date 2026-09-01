package com.artemchep.keyguard.common.service.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GpgOpenPgpNamesTest {
    @Test
    fun `normalizes conventional user id email addresses`() {
        assertEquals(
            "alice@example.com",
            normalizeGpgUserIdEmail(" Alice Example <ALICE@Example.COM>"),
        )
        assertEquals(
            "alice@example.com",
            normalizeGpgUserIdEmail("attacker@example.com <ALICE@example.com>"),
        )
        assertEquals(
            "alice@example.com",
            normalizeGpgUserIdEmail("Alice (work) <ALICE@example.com>"),
        )
        assertEquals(
            "müller@例子.测试",
            normalizeGpgUserIdEmail("MÜLLER@例子.测试"),
        )
    }

    @Test
    fun `normalizes bare mailbox inputs independently`() {
        assertEquals(
            "alice@example.com",
            normalizeGpgMailboxAddress("  ALICE@example.com "),
        )
        assertNull(normalizeGpgMailboxAddress("Alice <alice@example.com>"))
    }

    @Test
    fun `rejects ambiguous or malformed conventional user ids`() {
        listOf(
            "Name < a@b >",
            "Name <first@example.com> <second@example.com>",
            "Name <a@b> trailing@example.com",
            "<bad<good@example.com>",
            "Name <a@b> ",
            "\"first.last\"@example.com",
            " first@example.com ",
        ).forEach { value ->
            assertNull(normalizeGpgUserIdEmail(value), value)
        }
    }

    @Test
    fun `accepts punctuation utf8 and comments in conventional user ids`() {
        assertEquals(
            "first.last+tag@example.com",
            normalizeGpgUserIdEmail(
                "Acme Industries, Inc. (work) <first.last+tag@example.com>",
            ),
        )
        assertEquals(
            "emoji😀@example.com",
            normalizeGpgUserIdEmail("Emoji <emoji😀@example.com>"),
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
            ".a@b",
            "a.@b",
            "a..b@c",
            "a@.b",
            "a@b.",
        ).forEach { value ->
            assertNull(normalizeGpgUserIdEmail(value), value)
        }
    }
}
