package com.artemchep.keyguard.common.service.settings.impl

import com.artemchep.keyguard.common.service.keyvalue.VaultSettingsKeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.setAndCommit
import com.artemchep.keyguard.common.service.settings.VaultSettingsReadWriteRepository
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

class VaultSettingsRepositoryImpl(
    private val store: VaultSettingsKeyValueStore,
) : VaultSettingsReadWriteRepository {
    companion object {
        private const val KEY_HIBP_API_TOKEN = "hibp.api_token"
    }

    constructor(directDI: DirectDI) : this(
        store = directDI.instance(),
    )

    private val hibpApiTokenPref =
        store.getString(KEY_HIBP_API_TOKEN, "")

    override fun setHibpApiToken(
        token: String?,
    ) = hibpApiTokenPref
        .setAndCommit(token?.trim().orEmpty())

    override fun getHibpApiToken() = hibpApiTokenPref
        .map { token ->
            token.takeUnless { it.isEmpty() }
        }
}
