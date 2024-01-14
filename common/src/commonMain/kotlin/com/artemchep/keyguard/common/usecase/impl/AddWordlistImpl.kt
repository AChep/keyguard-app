package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AddWordlistRequest
import com.artemchep.keyguard.common.service.wordlist.repo.GeneratorWordlistRepository
import com.artemchep.keyguard.common.usecase.AddWordlist
import com.artemchep.keyguard.common.usecase.ReadWordlistFromFile
import org.kodein.di.DirectDI
import org.kodein.di.instance

class AddWordlistImpl(
    private val generatorWordlistRepository: GeneratorWordlistRepository,
    private val readWordlistFromFile: ReadWordlistFromFile,
) : AddWordlist {
    constructor(directDI: DirectDI) : this(
        generatorWordlistRepository = directDI.instance(),
        readWordlistFromFile = directDI.instance(),
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

            is AddWordlistRequest.Wordlist.FromList -> model.wordlist.list
        }
        generatorWordlistRepository
            .post(
                name = name,
                wordlist = wordlist,
            )
            .bind()
    }
}
