package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.usecase.ReadWordlistFromFile
import com.artemchep.keyguard.util.io.useLines

class ReadWordlistFromFileImpl(
    private val textService: TextService,
) : ReadWordlistFromFile {
    override fun invoke(
        uri: String,
    ): IO<List<String>> = ioEffect {
        textService.readFromFile(uri).useLines { lines ->
            with(ReadWordlistUtil) {
                lines.parseAsWordlist()
            }
        }
    }
}
