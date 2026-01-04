package com.artemchep.keyguard.common.service.gpmprivapps

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DPrivilegedApp
import com.artemchep.keyguard.provider.bitwarden.repository.BaseRepository

interface UserPrivilegedAppRepository : BaseRepository<DPrivilegedApp> {
    fun removeAll(): IO<Unit>

    fun removeByIds(
        ids: Set<String>,
    ): IO<Unit>
}
