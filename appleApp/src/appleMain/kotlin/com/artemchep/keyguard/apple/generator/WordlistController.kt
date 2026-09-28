package com.artemchep.keyguard.apple.generator

import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AddWordlistRequest
import com.artemchep.keyguard.common.model.EditWordlistRequest
import com.artemchep.keyguard.common.usecase.AddWordlist
import com.artemchep.keyguard.common.usecase.EditWordlist
import com.artemchep.keyguard.common.usecase.RemoveWordlistById

/** Feature mutations; list state and commands belong to navigation entries. */
internal class WordlistController(private val ctx: CoreContext) {
    /** Imports a new wordlist from a local file [uri]. */
    suspend fun addWordlistFromFile(name: String, uri: String) {
        val main = ctx.awaitMain()
        val addWordlist = main.sessionKoin.get<AddWordlist>()
        val request = AddWordlistRequest(
            name = name,
            wordlist = AddWordlistRequest.Wordlist.FromFile(uri = uri),
        )
        addWordlist(request).bind()
    }

    /** Imports a new wordlist by downloading it from [url]. */
    suspend fun addWordlistFromUrl(name: String, url: String) {
        val main = ctx.awaitMain()
        val addWordlist = main.sessionKoin.get<AddWordlist>()
        val request = AddWordlistRequest(
            name = name,
            wordlist = AddWordlistRequest.Wordlist.FromUrl(url = url),
        )
        addWordlist(request).bind()
    }

    /** Renames the wordlist with the given id. */
    suspend fun renameWordlist(id: Long, name: String) {
        val main = ctx.awaitMain()
        val editWordlist = main.sessionKoin.get<EditWordlist>()
        editWordlist(EditWordlistRequest(id = id, name = name)).bind()
    }

    /** Deletes the wordlists with the given ids. */
    suspend fun deleteWordlists(ids: List<Long>) {
        val main = ctx.awaitMain()
        val removeWordlistById = main.sessionKoin.get<RemoveWordlistById>()
        removeWordlistById(ids.toSet()).bind()
    }
}
