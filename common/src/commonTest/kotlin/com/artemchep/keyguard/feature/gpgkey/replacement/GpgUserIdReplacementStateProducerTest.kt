package com.artemchep.keyguard.feature.gpgkey.replacement

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GpgUserIdReplacementStateProducerTest {
    private val oldUserId = "Alice <alice@example.com>"
    private val activeUserIds = listOf(
        oldUserId,
        "Bob <bob@example.com>",
    )

    @Test
    fun `invalid OpenPGP User ID formats are rejected`() {
        listOf(
            "",
            "   ",
            "Alice\n<alice@example.com>",
            "\uD800",
            "a".repeat(1_025),
        ).forEach { newUserId ->
            assertEquals(
                GpgUserIdReplacementError.InvalidFormat,
                validateGpgUserIdReplacement(
                    oldUserId = oldUserId,
                    activeUserIds = activeUserIds,
                    newUserId = newUserId,
                ),
                "Expected an invalid format for ${newUserId.length} characters",
            )
        }
    }

    @Test
    fun `selected old identity is rejected before active duplicate`() {
        assertEquals(
            GpgUserIdReplacementError.SameIdentity,
            validateGpgUserIdReplacement(
                oldUserId = oldUserId,
                activeUserIds = activeUserIds,
                newUserId = oldUserId,
            ),
        )
    }

    @Test
    fun `another active identity is rejected as duplicate`() {
        assertEquals(
            GpgUserIdReplacementError.DuplicateIdentity,
            validateGpgUserIdReplacement(
                oldUserId = oldUserId,
                activeUserIds = activeUserIds,
                newUserId = "Bob <bob@example.com>",
            ),
        )
    }

    @Test
    fun `valid replacement preserves the exact input`() {
        val exactInput = "  Alice Cooper <alice@example.com>  "

        assertNull(
            validateGpgUserIdReplacement(
                oldUserId = oldUserId,
                activeUserIds = activeUserIds,
                newUserId = exactInput,
            ),
        )
        assertEquals(
            exactInput,
            confirmedGpgUserIdReplacement(
                oldUserId = oldUserId,
                activeUserIds = activeUserIds,
                newUserId = exactInput,
            ),
        )
    }

    @Test
    fun `format boundary is measured in UTF-8 bytes`() {
        val valid = "é".repeat(512)
        val invalid = "é".repeat(513)

        assertNull(
            validateGpgUserIdReplacement(
                oldUserId = oldUserId,
                activeUserIds = activeUserIds,
                newUserId = valid,
            ),
        )
        assertEquals(
            GpgUserIdReplacementError.InvalidFormat,
            validateGpgUserIdReplacement(
                oldUserId = oldUserId,
                activeUserIds = activeUserIds,
                newUserId = invalid,
            ),
        )
    }

    @Test
    fun `route args serialize only display values needed for validation`() {
        val args = GpgUserIdReplacementRoute.Args(
            oldUserId = oldUserId,
            activeUserIds = activeUserIds,
            initialValue = "Alice Cooper <alice@example.com>",
        )

        val restored = Json.decodeFromString<GpgUserIdReplacementRoute.Args>(
            Json.encodeToString(args),
        )

        assertEquals(args, restored)
    }
}
