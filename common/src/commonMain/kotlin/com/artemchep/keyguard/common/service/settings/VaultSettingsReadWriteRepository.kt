package com.artemchep.keyguard.common.service.settings

import com.artemchep.keyguard.common.io.IO

interface VaultSettingsReadWriteRepository : VaultSettingsReadRepository {
    fun setHibpApiToken(
        token: String?,
    ): IO<Unit>
}
