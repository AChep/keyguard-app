package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AddWordlistRequest
import com.artemchep.keyguard.common.service.wordlist.repo.GeneratorWordlistRepository
import com.artemchep.keyguard.common.usecase.AddWordlist
import com.artemchep.keyguard.common.usecase.ReadWordlistFromFile
import com.artemchep.keyguard.common.usecase.ReadWordlistFromUrl
import org.kodein.di.DirectDI
import org.kodein.di.instance

class AddWordlistImpl(
    private val generatorWordlistRepository: GeneratorWordlistRepository,
    private val readWordlistFromFile: ReadWordlistFromFile,
    private val readWordlistFromUrl: ReadWordlistFromUrl,
) : AddWordlist {
    constructor(directDI: DirectDI) : this(
        generatorWordlistRepository = directDI.instance(),
        readWordlistFromFile = directDI.instance(),
        readWordlistFromUrl = directDI.instance(),
    )

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
    }
}
