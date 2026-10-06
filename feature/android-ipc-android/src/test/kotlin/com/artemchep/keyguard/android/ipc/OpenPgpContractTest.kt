package com.artemchep.keyguard.android.ipc

import org.openintents.openpgp.util.OpenPgpApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenPgpContractTest {
    @Test
    fun `API versions seven through twelve are accepted`() {
        (7..12).forEach {
            assertTrue(isSupportedOpenPgpApiVersion(it))
        }
        assertFalse(isSupportedOpenPgpApiVersion(6))
        assertFalse(isSupportedOpenPgpApiVersion(13))
        assertFalse(isSupportedOpenPgpApiVersion(Int.MIN_VALUE))
    }

    @Test
    fun `K-9 Autocrypt recipient status action is supported`() {
        assertTrue(
            OpenPgpApi.ACTION_QUERY_AUTOCRYPT_STATUS in
                    OpenPgpService.SUPPORTED_ACTIONS,
        )
    }

    @Test
    fun `request normalization rejects unsupported extras and binds detached signature bytes`() {
        val first = normalizeOpenPgpExtras(
            apiVersion = 12,
            detachedSignature = byteArrayOf(1, 2, 3),
        )
        val second = normalizeOpenPgpExtras(
            apiVersion = 12,
            detachedSignature = byteArrayOf(3, 2, 1),
        )

        assertNotNull(first)
        assertNotNull(second)
        assertNotEquals(first.digestParts, second.digestParts)
        assertNull(
            normalizeOpenPgpExtras(
                apiVersion = 12,
                minimize = true,
            ),
        )
        assertNull(
            normalizeOpenPgpExtras(
                apiVersion = 12,
                hasCustomHeaders = true,
            ),
        )
    }

    @Test
    fun `request normalization limits collections and canonicalizes selected key ids`() {
        val normalized = normalizeOpenPgpExtras(
            apiVersion = 12,
            keyIds = longArrayOf(3L, 1L, 3L),
            selectedKeyIds = longArrayOf(2L, 1L),
            userIds = arrayOf(" ALICE@example.com "),
        )
        assertNotNull(normalized)
        assertEquals(listOf(3L, 1L, 2L), normalized.keyIds.toList())
        assertTrue("user_id=alice@example.com" in normalized.digestParts)
        assertNull(
            normalizeOpenPgpExtras(
                apiVersion = 12,
                keyIds = LongArray(65),
            ),
        )
    }

    @Test
    fun `emitted retry extras re-normalize to the same digest`() {
        val first = normalizeOpenPgpExtras(
            apiVersion = 12,
            keyIds = longArrayOf(3L, 1L),
            selectedKeyIds = longArrayOf(2L, 1L),
            userIds = arrayOf(" alice@example.com "),
            preselectKeyId = 42L,
            senderAddress = " Alice@Example.com ",
        )
        assertNotNull(first)
        // Feed back exactly what the retry intent would carry.
        val retried = normalizeOpenPgpExtras(
            apiVersion = 12,
            asciiArmor = first.asciiArmor,
            compression = first.compression,
            opportunistic = first.opportunistic,
            originalFilename = first.originalFilename,
            userIds = first.requestedEmails.toTypedArray(),
            keyIds = first.keyIds,
            signKeyId = first.signKeyId,
            preselectKeyId = first.preselectKeyId,
            keyId = first.keyId,
            senderAddress = first.senderAddress,
            detachedSignature = first.detachedSignature,
        )
        assertNotNull(retried)
        assertEquals(first.digestParts, retried.digestParts)
    }

    @Test
    fun `sender address is canonicalized and bound to the digest`() {
        val normalized = normalizeOpenPgpExtras(
            apiVersion = 12,
            senderAddress = " Alice@Example.com ",
        )
        assertNotNull(normalized)
        assertEquals("alice@example.com", normalized.senderAddress)

        val absent = normalizeOpenPgpExtras(apiVersion = 12)
        val suppliedEmpty = normalizeOpenPgpExtras(apiVersion = 12, senderAddress = "")
        val different = normalizeOpenPgpExtras(
            apiVersion = 12,
            senderAddress = "bob@example.com",
        )
        assertNotNull(absent)
        assertNotNull(suppliedEmpty)
        assertNotNull(different)
        assertNotEquals(absent.digestParts, suppliedEmpty.digestParts)
        assertNotEquals(normalized.digestParts, different.digestParts)
    }

    @Test
    fun `API user id extras accept conventional OpenPGP identity syntax`() {
        val normalized = normalizeOpenPgpExtras(
            apiVersion = 12,
            userIds = arrayOf("Alice <ALICE@example.com>"),
            allowUserIdSyntax = true,
        )
        assertNotNull(normalized)
        assertEquals(listOf("alice@example.com"), normalized.requestedEmails)
        assertTrue("user_id=alice@example.com" in normalized.digestParts)

        val addressOnly = normalizeOpenPgpExtras(
            apiVersion = 12,
            userIds = arrayOf("<ALICE@example.com>"),
            allowUserIdSyntax = true,
        )
        assertNotNull(addressOnly)
        assertEquals(listOf("alice@example.com"), addressOnly.requestedEmails)

        val bareMailbox = normalizeOpenPgpExtras(
            apiVersion = 12,
            userIds = arrayOf("ALICE@example.com"),
            allowUserIdSyntax = true,
        )
        assertNotNull(bareMailbox)
        assertEquals(normalized.requestedEmails, bareMailbox.requestedEmails)

        assertNull(
            normalizeOpenPgpExtras(
                apiVersion = 12,
                userIds = arrayOf("Alice <alice@example.com>"),
            ),
        )

        val sender = normalizeOpenPgpExtras(
            apiVersion = 12,
            senderAddress = "Alice <alice@example.com>",
        )
        assertNotNull(sender)
        assertEquals("", sender.senderAddress)
    }

    @Test
    fun `preselected signing key remains a chooser hint`() {
        val normalized = normalizeOpenPgpExtras(
            apiVersion = 12,
            preselectKeyId = 42L,
        )
        assertNotNull(normalized)
        assertNull(normalized.signKeyId)
        assertEquals(42L, normalized.preselectKeyId)
        assertTrue("preselect_key_id=42" in normalized.digestParts)
        assertEquals(emptyList(), normalized.approvalConstraintKeyIds)
    }

    @Test
    fun `approval selection honors exactly one preselected candidate`() {
        fun candidate(
            id: String,
            preselected: Boolean = false,
        ) = AndroidIpcApprovalCoordinator.Candidate(
            id = id,
            name = id,
            description = "",
            preselected = preselected,
        )

        assertEquals(
            setOf("second"),
            initialAndroidIpcApprovalSelection(
                listOf(candidate("first"), candidate("second", preselected = true)),
            ),
        )
        assertEquals(
            emptySet(),
            initialAndroidIpcApprovalSelection(
                listOf(
                    candidate("first", preselected = true),
                    candidate("second", preselected = true),
                ),
            ),
        )
    }

    @Test
    fun `combined key ids beyond the cap are rejected at admission`() {
        assertNull(
            normalizeOpenPgpExtras(
                apiVersion = 12,
                keyIds = LongArray(40) { it.toLong() },
                selectedKeyIds = LongArray(40) { (it + 100).toLong() },
            ),
        )
        // Overlapping ids dedupe below the cap and stay valid.
        assertNotNull(
            normalizeOpenPgpExtras(
                apiVersion = 12,
                keyIds = LongArray(40) { it.toLong() },
                selectedKeyIds = LongArray(40) { it.toLong() },
            ),
        )
    }

    @Test
    fun `action-specific extras are validated before approval`() {
        assertFalse(
            hasValidOpenPgpActionExtras(
                action = OpenPgpApi.ACTION_GET_KEY,
                keyId = null,
                hasDetachedSignature = false,
            ),
        )
        assertTrue(
            hasValidOpenPgpActionExtras(
                action = OpenPgpApi.ACTION_GET_KEY,
                keyId = 42L,
                hasDetachedSignature = false,
            ),
        )
        assertTrue(
            hasValidOpenPgpActionExtras(
                action = OpenPgpApi.ACTION_DECRYPT_VERIFY,
                keyId = null,
                hasDetachedSignature = true,
            ),
        )
        assertFalse(
            hasValidOpenPgpActionExtras(
                action = OpenPgpApi.ACTION_ENCRYPT,
                keyId = null,
                hasDetachedSignature = true,
            ),
        )
    }

    @Test
    fun `armor charset accepts installed canonical charset names`() {
        assertEquals("UTF-8", normalizeArmorCharset(" utf-8 "))
        assertEquals("ISO-8859-1", normalizeArmorCharset("ISO-8859-1"))
        assertNull(normalizeArmorCharset(""))
        assertNull(normalizeArmorCharset("not-a-real-charset"))
    }
}
