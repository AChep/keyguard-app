package com.artemchep.keyguard.feature.gpgkey.selection

import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_user_id_revocation_no_identity_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_no_identity_message
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GpgUserIdSelectionStateProducerTest {
    @Test
    fun `persisted stable id remains selected after identities reorder`() {
        val identities = listOf(
            identity(id = "identity-a", userId = "Alice <alice@example.com>"),
            identity(id = "identity-b", userId = "Bob <bob@example.com>"),
        )
        val reordered = identities.reversed()

        val result = confirmedGpgUserIdSelection(
            mode = GpgUserIdSelectionRoute.Args.Mode.Replacement,
            identities = reordered,
            selectedIdentityId = "identity-b",
        )

        assertEquals("identity-b", result)
    }

    @Test
    fun `confirmation returns the exact identity id rather than display value`() {
        val result = confirmedGpgUserIdSelection(
            mode = GpgUserIdSelectionRoute.Args.Mode.Replacement,
            identities = listOf(
                identity(
                    id = "v1:0123456789ABCDEF",
                    userId = "Alice <alice@example.com>",
                ),
            ),
            selectedIdentityId = "v1:0123456789ABCDEF",
        )

        assertEquals("v1:0123456789ABCDEF", result)
    }

    @Test
    fun `revocation cannot confirm the final active identity`() {
        val identities = listOf(
            identity(id = "identity-a", userId = "Alice <alice@example.com>"),
        )

        assertEquals(
            GpgUserIdSelectionError.LastIdentity,
            evaluateGpgUserIdSelection(
                mode = GpgUserIdSelectionRoute.Args.Mode.Revocation,
                identityCount = identities.size,
            ),
        )
        assertNull(
            confirmedGpgUserIdSelection(
                mode = GpgUserIdSelectionRoute.Args.Mode.Revocation,
                identities = identities,
                selectedIdentityId = "identity-a",
            ),
        )
    }

    @Test
    fun `replacement can select the only active identity`() {
        val identities = listOf(
            identity(id = "identity-a", userId = "Alice <alice@example.com>"),
        )

        assertEquals(
            "identity-a",
            confirmedGpgUserIdSelection(
                mode = GpgUserIdSelectionRoute.Args.Mode.Replacement,
                identities = identities,
                selectedIdentityId = "identity-a",
            ),
        )
    }

    @Test
    fun `missing or unknown selection cannot be confirmed`() {
        val identities = listOf(
            identity(id = "identity-a", userId = "Alice <alice@example.com>"),
        )

        assertNull(
            confirmedGpgUserIdSelection(
                mode = GpgUserIdSelectionRoute.Args.Mode.Replacement,
                identities = identities,
                selectedIdentityId = "identity-b",
            ),
        )
        assertNull(
            confirmedGpgUserIdSelection(
                mode = GpgUserIdSelectionRoute.Args.Mode.Replacement,
                identities = emptyList(),
                selectedIdentityId = "identity-a",
            ),
        )
    }

    @Test
    fun `empty identity message matches selection mode`() {
        assertEquals(
            Res.string.gpg_user_id_revocation_no_identity_message,
            gpgUserIdSelectionErrorResource(
                error = GpgUserIdSelectionError.NoIdentity,
                mode = GpgUserIdSelectionRoute.Args.Mode.Revocation,
            ),
        )
        assertEquals(
            Res.string.gpg_user_id_replacement_no_identity_message,
            gpgUserIdSelectionErrorResource(
                error = GpgUserIdSelectionError.NoIdentity,
                mode = GpgUserIdSelectionRoute.Args.Mode.Replacement,
            ),
        )
    }

    @Test
    fun `route args serialize lightweight active identities`() {
        val args = GpgUserIdSelectionRoute.Args(
            activeIdentities = listOf(
                identity(id = "identity-a", userId = "Alice <alice@example.com>"),
                identity(id = "identity-b", userId = "Bob <bob@example.com>"),
            ),
            mode = GpgUserIdSelectionRoute.Args.Mode.Revocation,
        )

        val restored = Json.decodeFromString<GpgUserIdSelectionRoute.Args>(
            Json.encodeToString(args),
        )

        assertEquals(args, restored)
    }

    private fun identity(
        id: String,
        userId: String,
    ) = GpgUserIdSelectionIdentity(
        identityId = id,
        userId = userId,
    )
}
