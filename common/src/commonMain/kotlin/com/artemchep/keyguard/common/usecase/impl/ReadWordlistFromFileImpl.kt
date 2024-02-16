package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.service.text.readFromFileAsText
import com.artemchep.keyguard.common.usecase.ReadWordlistFromFile
import org.kodein.di.DirectDI
import org.kodein.di.instance

class ReadWordlistFromFileImpl(
    private val textService: TextService,
) : ReadWordlistFromFile {
    constructor(directDI: DirectDI) : this(
        textService = directDI.instance(),
    )

    override fun invoke(
        uri: String,
    ): IO<List<String>> = ioEffect {
        val content = textService.readFromFileAsText(uri)
        with(content) {
            ReadWordlistUtil.parseAsWordlist()
        }
    }
}
