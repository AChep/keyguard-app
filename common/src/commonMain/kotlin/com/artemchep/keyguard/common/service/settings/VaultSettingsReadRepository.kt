package com.artemchep.keyguard.common.service.settings

import kotlinx.coroutines.flow.Flow

interface VaultSettingsReadRepository {
    fun getHibpApiToken(): Flow<String?>
}
