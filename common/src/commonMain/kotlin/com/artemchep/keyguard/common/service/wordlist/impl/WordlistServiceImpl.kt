package com.artemchep.keyguard.common.service.wordlist.impl

import arrow.core.partially1
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.handleErrorTap
import com.artemchep.keyguard.common.io.sharedSoftRef
import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.service.wordlist.WordlistService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kodein.di.DirectDI
import org.kodein.di.instance

class WordlistServiceImpl(
    private val textService: TextService,
) : WordlistService {
    companion object {
        private const val TAG = "WordlistService"
    }

    private val wordListIo = ::loadWordlist
        .partially1(textService)
        .handleErrorTap {
            it.printStackTrace()
        }
        .sharedSoftRef(TAG)

    constructor(
        directDI: DirectDI,
    ) : this(
        textService = directDI.instance(),
    )

    override fun get(): IO<List<String>> = wordListIo
}

private suspend fun loadWordlist(
    textService: TextService,
) = withContext(Dispatchers.IO) {
    textService.readFromResources(FileResource.wordlist).use {
        it
            .bufferedReader()
            .lineSequence()
            .filter { line -> line.isNotEmpty() }
            .toList()
    }
}
