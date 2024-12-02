package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenEquivalentDomain
import com.artemchep.keyguard.provider.bitwarden.entity.GlobalEquivalentDomainEntity

fun BitwardenEquivalentDomain.Companion.encrypted(
    accountId: String,
    entryId: String,
    entity: GlobalEquivalentDomainEntity,
) = kotlin.run {
    val domains = entity.domains.orEmpty()
    val type = BitwardenEquivalentDomain.Type.Global(
        type = entity.type,
    )
    BitwardenEquivalentDomain(
        accountId = accountId,
        entryId = entryId,
        // fields
        excluded = entity.excluded,
        domains = domains,
        type = type,
    )
}

fun BitwardenEquivalentDomain.Companion.encrypted(
    accountId: String,
    entryId: String,
    domains: List<String>,
) = kotlin.run {
    val type = BitwardenEquivalentDomain.Type.Custom
    BitwardenEquivalentDomain(
        accountId = accountId,
        entryId = entryId,
        // fields
        excluded = false,
        domains = domains,
        type = type,
    )
}
