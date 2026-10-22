package com.artemchep.keyguard.common.service.environment

/** The local ID of an account by its remote ID, its local ID, or else its exact, unique name. */
internal fun EnvironmentCatalog.accountId(account: String): String = accounts
    .findUnique(account, "account_not_found", "ambiguous_account", { it.remoteId }, { it.id }, { it.name })
    .id

/** The local ID of an environment by its remote ID, or else its local ID; optionally in one account. */
internal fun EnvironmentCatalog.environmentId(
    environment: String,
    accountId: String? = null,
): String = environments
    .filter { accountId == null || it.accountId == accountId }
    .findUnique(environment, "not_found", "ambiguous_environment", { it.remoteId }, { it.id })
    .id

/** The first key that matches anything decides; it must match exactly one item. */
private fun <T> List<T>.findUnique(
    value: String,
    notFound: String,
    ambiguous: String,
    vararg keys: (T) -> String?,
): T {
    val matches = keys.firstNotNullOfOrNull { key -> filter { key(it) == value }.ifEmpty { null } }
        ?: throw EnvironmentServiceException(notFound)
    return matches.singleOrNull() ?: throw EnvironmentServiceException(ambiguous)
}
