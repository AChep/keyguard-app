package com.artemchep.keyguard.provider.bitwarden.sync.v2

/**
 * Filters entities to only those belonging to the given [accountId].
 *
 * Used after querying by account ID from the database to guard
 * against data from other accounts leaking into a sync operation
 * (defense-in-depth).
 */
internal fun <T> Iterable<T>.filterByAccountId(
    accountId: String,
    getAccountId: (T) -> String,
): List<T> = filter { entity -> getAccountId(entity) == accountId }
