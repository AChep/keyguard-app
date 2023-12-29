package com.artemchep.keyguard.provider.bitwarden.mapper

import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.provider.bitwarden.api.builder.buildHost
import com.artemchep.keyguard.provider.bitwarden.api.builder.buildIconsRequestUrl
import com.artemchep.keyguard.provider.bitwarden.api.builder.buildWebVaultUrl

fun BitwardenToken.toDomain(): DAccount {
    val environment = env.back()
    return DAccount(
        id = AccountId(id),
        username = user.email,
        host = environment.buildHost(),
        url = environment.buildWebVaultUrl(),
        faviconServer = { domain ->
            environment
                .buildIconsRequestUrl(domain)
        },
    )
}
