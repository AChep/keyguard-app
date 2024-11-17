package com.artemchep.keyguard.common.service.dirs

import com.artemchep.keyguard.common.io.IO
import java.io.OutputStream

interface DirsService {
    fun saveToDownloads(
        fileName: String,
        write: suspend (OutputStream) -> Unit,
    ): IO<String?>
}
