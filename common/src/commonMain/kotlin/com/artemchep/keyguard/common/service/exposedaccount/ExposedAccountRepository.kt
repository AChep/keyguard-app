package com.artemchep.keyguard.common.service.exposedaccount

import com.artemchep.keyguard.common.io.IO
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes the exposed database's mirror of the vault's accounts.
 *
 * Everything here is reachable **while the vault is locked** — that is the entire
 * point of the mirror. Writes only happen while it is unlocked, from
 * [ExposedAccountSyncer].
 */
interface ExposedAccountRepository {
    /**
     * The mirrored accounts, ordered by label.
     */
    fun get(): Flow<List<ExposedAccount>>

    /**
     * Resolves a credential-transfer entry id, or `null` when the id was never
     * registered by this installation.
     *
     * A non-null result whose [ExposedAccountEntry.account] is `null` means the
     * id is genuinely ours but its account is no longer available.
     */
    fun resolveEntry(entryId: String): IO<ExposedAccountEntry?>

    /**
     * The mirrored, non-hidden accounts paired with their stable entry ids.
     *
     * This is a reactive view of one committed database snapshot. Entry ids are
     * minted by [replaceAll] in the same transaction that writes the account
     * mirror, so consumers never have to race an independent read against the
     * mirror writer.
     */
    fun getRegistrations(): Flow<List<ExposedAccountRegistration>>

    /**
     * Replaces the mirrored accounts with [accounts], and mints an entry id for
     * every account in [allAccountIds] that lacks one.
     *
     * [allAccountIds] deliberately includes hidden accounts: their labels must not
     * be mirrored, but their entry ids have to exist so a stale pick can still be
     * recognised. Existing entry ids are never rewritten.
     */
    fun replaceAll(
        accounts: List<ExposedAccount>,
        allAccountIds: Set<String>,
    ): IO<Unit>
}

/**
 * The complete account identity advertised to the credential-transfer picker.
 *
 * Keeping only registration-relevant fields makes structural equality a precise
 * change key: unrelated profile changes do not cause another platform IPC, while
 * an entry-id rotation cannot be missed.
 */
data class ExposedAccountRegistration(
    val accountId: String,
    val entryId: String,
    val label: String,
)
