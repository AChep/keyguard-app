package com.artemchep.keyguard.feature.home.vault.add

import com.artemchep.keyguard.common.model.DSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AddStateProducerUriSignatureTest {
    @Test
    fun `unchanged uri preserves hidden signatures`() {
        val signatures = listOf(
            DSecret.Uri.Signature(FINGERPRINT),
        )
        val initial = DSecret.Uri(
            uri = "androidapp://com.example.app",
            match = DSecret.Uri.MatchType.Exact,
            signatures = signatures,
        )

        val output = createUriWithPreservedSignatures(
            uri = "androidapp://com.example.app",
            match = DSecret.Uri.MatchType.Host,
            initial = initial,
        )

        assertEquals(signatures, output.signatures)
    }

    @Test
    fun `changed uri clears hidden signatures`() {
        val initial = DSecret.Uri(
            uri = "androidapp://com.example.app",
            signatures = listOf(
                DSecret.Uri.Signature(FINGERPRINT),
            ),
        )

        val output = createUriWithPreservedSignatures(
            uri = "androidapp://com.example.other",
            match = null,
            initial = initial,
        )

        assertTrue(output.signatures.isEmpty())
    }

    private companion object {
        const val FINGERPRINT =
            "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"
    }
}
