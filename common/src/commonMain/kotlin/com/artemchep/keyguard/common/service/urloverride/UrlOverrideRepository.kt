package com.artemchep.keyguard.common.service.urloverride

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DGlobalUrlOverride
import com.artemchep.keyguard.provider.bitwarden.repository.BaseRepository

interface UrlOverrideRepository : BaseRepository<DGlobalUrlOverride> {
    fun removeAll(): IO<Unit>

    fun removeByIds(
        ids: Set<String>,
    ): IO<Unit>
}
