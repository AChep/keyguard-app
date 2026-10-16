package com.artemchep.keyguard.common.service.gpgagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpgAgentKeySelectionTest {
    @Test
    fun `discovery combines capabilities and keeps stable display metadata across copies`() {
        val candidates = listOf(
            key("0-public"),
            key("signer", canSign = true),
            key("decryptor", canDecrypt = true).copy(keygrip = KEYGRIP.lowercase()),
        )

        val keys = candidates.toGpgAgentKeys()
        assertEquals(keys, candidates.reversed().toGpgAgentKeys())
        val combined = keys.single()
        assertEquals(KEYGRIP, combined.keygrip)
        assertEquals("decryptor", combined.name)
        assertTrue(combined.canSign)
        assertTrue(combined.canDecrypt)
    }

    @Test
    fun `public-only copies do not advertise private operations`() {
        val combined = listOf(key("b"), key("a")).toGpgAgentKeys().single()
        assertEquals("a", combined.name)
        assertFalse(combined.canSign)
        assertFalse(combined.canDecrypt)
        assertEquals(emptyList(), emptyList<GpgAgentKeyInfoRow>().toGpgAgentKeys())
    }

    @Test
    fun `component keygrips remain separate even when fingerprints match`() {
        val keys = listOf(
            key("a", canSign = true),
            key("b", canDecrypt = true).copy(keygrip = OTHER_KEYGRIP),
        ).toGpgAgentKeys()

        assertEquals(listOf(KEYGRIP, OTHER_KEYGRIP), keys.map { it.keygrip })
        assertTrue(keys.first().canSign)
        assertFalse(keys.first().canDecrypt)
        assertFalse(keys.last().canSign)
        assertTrue(keys.last().canDecrypt)
    }

    @Test
    fun `cached approvals select by operation then account and item identity`() {
        val signer = key("b", canSign = true)
        val decryptor = key("c", canDecrypt = true)
        val candidates = listOf(
            key("0-public"),
            signer.copy(accountId = "z-account", cipherId = "a"),
            signer.copy(cipherId = "z-item"),
            decryptor,
            signer,
        )

        listOf(candidates, candidates.reversed()).forEach { ordered ->
            assertEquals(signer, ordered.selectGpgAgentKeySource(GpgAgentOperation.SIGN))
            assertEquals(decryptor, ordered.selectGpgAgentKeySource(GpgAgentOperation.DECRYPT))
        }
        assertNull(listOf(decryptor).selectGpgAgentKeySource(GpgAgentOperation.SIGN))
        assertNull(listOf(signer).selectGpgAgentKeySource(GpgAgentOperation.DECRYPT))
    }

    private fun key(
        id: String,
        canSign: Boolean = false,
        canDecrypt: Boolean = false,
    ) = GpgAgentKeyInfoRow(
        accountId = "account",
        cipherId = id,
        keygrip = KEYGRIP,
        fingerprint = FINGERPRINT,
        algorithm = "RSA",
        canSign = canSign,
        canDecrypt = canDecrypt,
        name = id,
    )

    private companion object {
        const val KEYGRIP = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"
        const val OTHER_KEYGRIP = "ABCDEF0123456789ABCDEF0123456789ABCDEF02"
        const val FINGERPRINT = "0123456789ABCDEF0123456789ABCDEF01234567"
    }
}
