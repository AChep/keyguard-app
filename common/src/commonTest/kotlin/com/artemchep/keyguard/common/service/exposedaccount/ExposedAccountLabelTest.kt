package com.artemchep.keyguard.common.service.exposedaccount

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [ExposedAccount.label] names an account somewhere the app cannot re-render a
 * placeholder afterwards — a system picker row, a consent screen shown before
 * unlock — so each rung of the fallback has to be reachable.
 *
 * This is also the only implementation of that chain, and it is read while the
 * vault is locked. It was previously duplicated on `DProfile`, with the two
 * compared fail-closed against each other; these cases exist so a change to the
 * chain is a decision rather than a silent one.
 */
class ExposedAccountLabelTest {
    @Test
    fun `a name wins`() {
        val account = exposedAccount(name = "Personal", email = "alice@example.com")
        assertEquals("Personal", account.label)
    }

    @Test
    fun `a blank name falls back to the email`() {
        val account = exposedAccount(name = "   ", email = "alice@example.com")
        assertEquals("alice@example.com", account.label)
    }

    @Test
    fun `a nameless and email-less account falls back to the host`() {
        // The real case this exists for: a KeePass database with no title and no
        // default user, where the file name is all there is to show.
        val account = exposedAccount(name = "", email = "", host = "Personal.kdbx")
        assertEquals("Personal.kdbx", account.label)
    }

    @Test
    fun `a blank email is skipped rather than shown`() {
        val account = exposedAccount(name = "", email = "  ", host = "vault.example.com")
        assertEquals("vault.example.com", account.label)
    }

    @Test
    fun `nothing to show comes back blank rather than fabricated`() {
        // The chain is best-effort: `host` carries no non-blank guarantee either.
        // Callers that cannot render a blank substitute their own placeholder, as
        // `CredentialExchangeRegistry` does with the app name — so this must stay
        // blank rather than invent a value the picker would then have to undo.
        val account = exposedAccount(name = "", email = "", host = "")
        assertEquals("", account.label)
    }

    private fun exposedAccount(
        name: String,
        email: String,
        host: String = "vault.example.com",
    ) = ExposedAccount(
        accountId = "account-1",
        name = name,
        email = email,
        host = host,
    )
}
