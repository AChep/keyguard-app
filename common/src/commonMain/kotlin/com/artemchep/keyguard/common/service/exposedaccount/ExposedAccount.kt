package com.artemchep.keyguard.common.service.exposedaccount

/**
 * An account as mirrored into the exposed database, readable while the vault is
 * locked.
 *
 * Holds the raw pieces rather than a formatted label so each consumer can present
 * them its own way — a system picker row, a consent header, a locked-state
 * autofill suggestion.
 */
data class ExposedAccount(
    val accountId: String,
    val name: String,
    val email: String,
    val host: String,
) {
    /**
     * How to name this account outside the app — a system picker row, a consent
     * screen shown before the vault is unlocked.
     *
     * [name] can be blank for a KeePass database with no title, and [email] with
     * it when there is no default user either, which is why [host] is the last
     * rung: it is populated for both providers, as the server host for Bitwarden
     * and the database file name for KeePass. That still is not a guarantee, so
     * a caller with nowhere to put a blank must substitute its own placeholder —
     * `CredentialExchangeRegistry` does, with the app name.
     *
     * Deliberately plain Kotlin rather than a translated resource: this value is
     * part of [ExposedAccountRegistration]'s change-detection key, and a
     * locale-dependent string would make that key move with the device
     * configuration.
     *
     * This is the only implementation of the fallback chain. It used to be
     * duplicated as a `DProfile.entryLabel` computed on the unlocked vault and
     * compared against this one; the comparison was fail-closed, so any drift
     * between the two would have silently withdrawn Keyguard from the picker for
     * good. The registration now reads this value only.
     */
    val label: String
        get() = name.takeIf { it.isNotBlank() }
            ?: email.takeIf { it.isNotBlank() }
            ?: host
}

/**
 * The outcome of resolving a credential-transfer entry id back to an account.
 *
 * [account] is `null` when the id is known but the account it addressed is no
 * longer mirrored — hidden, or deleted. That is a different situation from an
 * unknown id (which is a caller that was never registered and must be rejected),
 * and the difference is what lets the export screen explain itself instead of
 * failing the caller outright.
 */
data class ExposedAccountEntry(
    val accountId: String,
    val account: ExposedAccount?,
)
