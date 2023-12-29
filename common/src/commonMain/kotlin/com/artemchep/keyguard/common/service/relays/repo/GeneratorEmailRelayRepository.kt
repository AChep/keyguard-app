package com.artemchep.keyguard.common.service.relays.repo

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DGeneratorEmailRelay
import com.artemchep.keyguard.provider.bitwarden.repository.BaseRepository

interface GeneratorEmailRelayRepository : BaseRepository<DGeneratorEmailRelay> {
    fun removeAll(): IO<Unit>

    fun removeByIds(
        ids: Set<String>,
    ): IO<Unit>
}
