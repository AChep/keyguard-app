package com.artemchep.keyguard.common.service.dirs

import com.artemchep.keyguard.common.io.IO
import kotlinx.io.Sink

interface DirsService {
    fun saveToDownloads(
        fileName: String,
        write: suspend (Sink) -> Unit,
    ): IO<String?>
}
