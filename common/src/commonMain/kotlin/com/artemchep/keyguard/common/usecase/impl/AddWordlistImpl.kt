package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AddWordlistRequest
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.file.deleteDroppedCopy
import com.artemchep.keyguard.common.service.wordlist.repo.GeneratorWordlistRepository
import com.artemchep.keyguard.common.usecase.AddWordlist
import com.artemchep.keyguard.common.usecase.ReadWordlistFromFile
import com.artemchep.keyguard.common.usecase.ReadWordlistFromUrl

class AddWordlistImpl(
    private val generatorWordlistRepository: GeneratorWordlistRepository,
    private val readWordlistFromFile: ReadWordlistFromFile,
    private val readWordlistFromUrl: ReadWordlistFromUrl,
    private val fileService: FileService,
) : AddWordlist {
    override fun invoke(
        model: AddWordlistRequest,
    ) = ioEffect {
        val name = model.name
        val wordlist = when (model.wordlist) {
            is AddWordlistRequest.Wordlist.FromFile -> {
                val uri = model.wordlist.uri
                readWordlistFromFile(uri)
                    .bind()
            }

            is AddWordlistRequest.Wordlist.FromUrl -> {
                val uri = model.wordlist.url
                readWordlistFromUrl(uri)
                    .bind()
            }

            is AddWordlistRequest.Wordlist.FromList -> model.wordlist.list
        }
        val invalidWordlist = wordlist.any { it.length > 512 }
        if (invalidWordlist) {
            throw IllegalStateException("Failed to parse the wordlist!")
        }
        generatorWordlistRepository
            .post(
                name = name,
                wordlist = wordlist,
            )
            .bind()
        // A dropped file may arrive as a managed copy that only this
        // import needs. A failed import keeps it, so it can be retried.
        if (model.wordlist is AddWordlistRequest.Wordlist.FromFile) {
            fileService.deleteDroppedCopy(model.wordlist.uri)
        }
    }
}
