package com.artemchep.keyguard.common.service.urlblock

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.provider.bitwarden.repository.BaseRepository

interface UrlBlockRepository : BaseRepository<DGlobalUrlBlock> {
    fun removeAll(): IO<Unit>

    fun removeByIds(
        ids: Set<String>,
    ): IO<Unit>
}
