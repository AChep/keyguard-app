package com.artemchep.keyguard.apple.generator

import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.exception.WordlistWordException
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AddWordlistRequest
import com.artemchep.keyguard.common.model.EditWordlistRequest
import com.artemchep.keyguard.common.model.EditWordlistWordsRequest
import com.artemchep.keyguard.common.usecase.AddWordlist
import com.artemchep.keyguard.common.usecase.EditWordlist
import com.artemchep.keyguard.common.usecase.EditWordlistWords
import com.artemchep.keyguard.common.usecase.RemoveWordlistById
import com.artemchep.keyguard.feature.generator.wordlist.util.WordlistUtil
import com.artemchep.keyguard.feature.generator.wordlist.util.translate
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.platform.LeContext

/** Feature mutations; list state and commands belong to navigation entries. */
internal class WordlistController(private val ctx: CoreContext) {
    suspend fun addWordlistFromFile(name: String, uri: String) {
        val main = ctx.awaitMain()
        val addWordlist = main.sessionKoin.get<AddWordlist>()
        val request = AddWordlistRequest(
            name = name,
            wordlist = AddWordlistRequest.Wordlist.FromFile(uri = uri),
        )
        addWordlist(request).bind()
    }

    suspend fun addWordlistFromUrl(name: String, url: String) {
        val main = ctx.awaitMain()
        val addWordlist = main.sessionKoin.get<AddWordlist>()
        val request = AddWordlistRequest(
            name = name,
            wordlist = AddWordlistRequest.Wordlist.FromUrl(url = url),
        )
        addWordlist(request).bind()
    }

    suspend fun renameWordlist(id: Long, name: String) {
        val main = ctx.awaitMain()
        val editWordlist = main.sessionKoin.get<EditWordlist>()
        editWordlist(EditWordlistRequest(id = id, name = name)).bind()
    }

    suspend fun deleteWordlists(ids: List<Long>) {
        val main = ctx.awaitMain()
        val removeWordlistById = main.sessionKoin.get<RemoveWordlistById>()
        removeWordlistById(ids.toSet()).bind()
    }

    suspend fun addWords(wordlistId: Long, text: String) = editWords(
        EditWordlistWordsRequest(
            wordlistId = wordlistId,
            add = WordlistUtil.parseWords(text),
        ),
    )

    suspend fun editWord(wordlistId: Long, from: String, to: String) = editWords(
        EditWordlistWordsRequest(
            wordlistId = wordlistId,
            remove = setOf(from),
            add = listOf(to),
        ),
    )

    suspend fun deleteWord(wordlistId: Long, word: String) = editWords(
        EditWordlistWordsRequest(
            wordlistId = wordlistId,
            remove = setOf(word),
        ),
    )

    private suspend fun editWords(request: EditWordlistWordsRequest) {
        val main = ctx.awaitMain()
        val editWordlistWords = main.sessionKoin.get<EditWordlistWords>()
        try {
            editWordlistWords(request).bind()
        } catch (e: WordlistWordException) {
            // Swift shows the message of the error as is.
            val translator = TranslatorScope.of(ctx.koin.get<LeContext>())
            throw IllegalArgumentException(translator.translate(e.error), e)
        }
    }
}
