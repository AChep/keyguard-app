package com.artemchep.keyguard.android.downloader.journal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DGeneratorHistory
import com.artemchep.keyguard.provider.bitwarden.repository.BaseRepository
import kotlin.time.Instant

interface GeneratorHistoryRepository : BaseRepository<DGeneratorHistory> {
    fun removeOlderThan(cutoff: Instant): IO<Unit>

    fun removeAll(): IO<Unit>

    fun removeByIds(
        ids: Set<String>,
    ): IO<Unit>
}
