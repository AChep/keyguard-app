package com.artemchep.keyguard.android.downloader.journal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DGeneratorHistory
import com.artemchep.keyguard.provider.bitwarden.repository.BaseRepository

interface GeneratorHistoryRepository : BaseRepository<DGeneratorHistory> {
    fun removeAll(): IO<Unit>

    fun removeByIds(
        ids: Set<String>,
    ): IO<Unit>
}
