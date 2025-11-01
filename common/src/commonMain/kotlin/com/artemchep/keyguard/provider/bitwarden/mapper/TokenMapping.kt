package com.artemchep.keyguard.provider.bitwarden.mapper

import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.provider.bitwarden.api.builder.buildHost
import com.artemchep.keyguard.provider.bitwarden.api.builder.buildIconsRequestUrl
import com.artemchep.keyguard.provider.bitwarden.api.builder.buildWebVaultUrl

fun ServiceToken.toDomain(): DAccount = when (this) {
    is BitwardenToken -> toDomain()
    is KeePassToken -> toDomain()
}

fun BitwardenToken.toDomain(): DAccount {
    val environment = env.back()
    return DAccount(
        id = AccountId(id),
        username = user.email,
        host = environment.buildHost(),
        webVaultUrl = environment.buildWebVaultUrl(),
        localVaultUrl = null,
        type = AccountType.BITWARDEN,
        faviconServer = { domain ->
            environment
                .buildIconsRequestUrl(domain)
        },
    )
}

fun KeePassToken.toDomain(): DAccount {
    val host = getHostName()
    return DAccount(
        id = AccountId(id),
        username = null,
        host = host,
        webVaultUrl = null,
        localVaultUrl = files.databaseUri,
        type = AccountType.KEEPASS,
        faviconServer = null,
    )
}

fun KeePassToken.getHostName(): String = kotlin.run {
    // Focus on the dedicated file name
    // property if it is available.
    if (files.databaseFileName.isNotEmpty()) {
        return@run files.databaseFileName
    }

    val regex = ".*/(.+)\\??.*".toRegex()
    val result = regex.matchEntire(files.databaseUri)
    result?.groupValues?.getOrNull(1)
        ?: files.databaseUri
}
