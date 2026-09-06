package com.artemchep.keyguard.common.service.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpgOpenPgpRecipientLookupTest {
    @Test
    fun `user id and mailbox normalization are distinct`() {
        assertEquals(
            "alice@example.com",
            normalizeGpgUserIdEmail(" Alice Example <ALICE@Example.COM>"),
        )
        assertEquals(
            "alice@example.com",
            normalizeGpgMailboxAddress("  ALICE@example.com "),
        )
        assertNull(normalizeGpgUserIdEmail("Alice Example"))
        assertNull(normalizeGpgMailboxAddress("Alice <alice@example.com>"))
    }

    @Test
    fun `recipient lookup resolves certified emails and direct key ids consistently`() {
        val alice = Candidate(
            name = "alice",
            ids = setOf(1L, 11L),
            emails = listOf("alice@example.com"),
        )
        val bob = Candidate(
            name = "bob",
            ids = setOf(2L, 22L),
            emails = listOf("bob@example.com"),
        )

        val result = resolveOpenPgpRecipients(
            recipientEmails = listOf(" ALICE@EXAMPLE.COM "),
            keyIds = listOf(22L),
            candidates = listOf(alice, bob),
            candidateEmails = Candidate::emails,
            candidateKeyIds = Candidate::ids,
            canEncrypt = Candidate::canEncrypt,
        )

        assertEquals(listOf(bob, alice), result.selected)
        assertTrue(
            result.details.all {
                it.outcome == OpenPgpRecipientLookupOutcome.RESOLVED
            },
        )
    }

    @Test
    fun `recipient lookup distinguishes missing ambiguous and unusable keys`() {
        val duplicateA = Candidate(
            name = "duplicate-a",
            ids = setOf(1L),
            emails = listOf("duplicate@example.com"),
        )
        val duplicateB = Candidate(
            name = "duplicate-b",
            ids = setOf(2L),
            emails = listOf("duplicate@example.com"),
        )
        val unusable = Candidate(
            name = "unusable",
            ids = setOf(3L),
            emails = listOf("unusable@example.com"),
            canEncrypt = false,
        )

        val result = resolveOpenPgpRecipients(
            recipientEmails = listOf(
                "missing@example.com",
                "duplicate@example.com",
                "unusable@example.com",
                "not-an-email",
                "Alice <alice@example.com>",
            ),
            keyIds = emptyList(),
            candidates = listOf(duplicateA, duplicateB, unusable),
            candidateEmails = Candidate::emails,
            candidateKeyIds = Candidate::ids,
            canEncrypt = Candidate::canEncrypt,
        )

        assertNull(result.selected)
        assertEquals(
            listOf(
                OpenPgpRecipientLookupOutcome.MISSING,
                OpenPgpRecipientLookupOutcome.AMBIGUOUS,
                OpenPgpRecipientLookupOutcome.NOT_ENCRYPTION_CAPABLE,
                OpenPgpRecipientLookupOutcome.INVALID,
                OpenPgpRecipientLookupOutcome.INVALID,
            ),
            result.details.map(OpenPgpRecipientLookupDetail::outcome),
        )
        assertFalse(result.isAmbiguousOnly)
    }

    @Test
    fun `ambiguous-only lookup requests interactive selection`() {
        val candidates = listOf(
            Candidate(
                name = "first",
                ids = setOf(1L),
                emails = listOf("duplicate@example.com"),
            ),
            Candidate(
                name = "second",
                ids = setOf(2L),
                emails = listOf("duplicate@example.com"),
            ),
        )

        val result = resolveOpenPgpRecipients(
            recipientEmails = listOf("duplicate@example.com"),
            keyIds = emptyList(),
            candidates = candidates,
            candidateEmails = Candidate::emails,
            candidateKeyIds = Candidate::ids,
            canEncrypt = Candidate::canEncrypt,
        )

        assertNull(result.selected)
        assertTrue(result.isAmbiguousOnly)
        assertTrue(
            selectedRingsCoverOpenPgpRecipients(
                recipientEmails = listOf("duplicate@example.com"),
                keyIds = emptyList(),
                selected = listOf(candidates.first()),
                candidateEmails = Candidate::emails,
                candidateKeyIds = Candidate::ids,
                canEncrypt = Candidate::canEncrypt,
            ),
        )
    }

    @Test
    fun `recipient diagnostics never include the recipient address`() {
        val email = "Sensitive.Recipient+mail@example.com"
        val detail = resolveOpenPgpRecipients(
            recipientEmails = listOf(email),
            keyIds = emptyList(),
            candidates = emptyList<Candidate>(),
            candidateEmails = Candidate::emails,
            candidateKeyIds = Candidate::ids,
            canEncrypt = Candidate::canEncrypt,
        ).details.single()

        val message = openPgpRecipientLookupLogMessage(
            requestReference = "request-ref",
            detail = detail,
        )

        assertFalse(email in message)
        assertFalse(email.lowercase() in message)
        assertTrue("recipient=${openPgpRecipientReference(email)}" in message)
        assertTrue("outcome=missing" in message)
    }

    private data class Candidate(
        val name: String,
        val ids: Set<Long>,
        val emails: List<String> = emptyList(),
        val canEncrypt: Boolean = true,
    )
}
