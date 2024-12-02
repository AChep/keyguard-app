package com.artemchep.keyguard.provider.bitwarden.mapper

import com.artemchep.keyguard.common.model.DEquivalentDomains
import com.artemchep.keyguard.core.store.bitwarden.BitwardenEquivalentDomain

fun BitwardenEquivalentDomain.toDomain(): DEquivalentDomains {
    val global = type is BitwardenEquivalentDomain.Type.Global
    return DEquivalentDomains(
        id = entryId,
        accountId = accountId,
        global = global,
        excluded = excluded,
        domains = domains,
    )
}
