package com.artemchep.keyguard.common.service.exposedaccount

/**
 * An account as mirrored into the exposed database, readable while the vault is
 * locked.
 *
 * Only the [name] is mirrored, not the email or the server host. The name can be
 * blank, for a KeePass database with no title or a Bitwarden profile with no
 * name. A caller that cannot show a blank substitutes generic wording, as
 * `CredentialExchangeRegistry` does with the app name.
 *
 * The substitute is applied by the caller rather than stored here: the name is
 * part of [ExposedAccountRegistration]'s change-detection key, and a
 * locale-dependent value would make that key move with the device
 * configuration.
 */
data class ExposedAccount(
    val accountId: String,
    val name: String,
)

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
