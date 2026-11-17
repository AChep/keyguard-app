package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.exception.WordlistWordException
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.EditWordlistWordsRequest
import com.artemchep.keyguard.common.model.WordlistWordError
import com.artemchep.keyguard.common.service.wordlist.repo.impl.GeneratorWordlistRepositoryImpl
import com.artemchep.keyguard.common.service.wordlist.repo.impl.GeneratorWordlistWordRepositoryImpl
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import com.artemchep.keyguard.provider.keyguard.session.SerializedTestDatabaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

// One function per scenario.
@Suppress("TooManyFunctions")
class EditWordlistWordsImplTest {
    private val db = createUploadTestDatabase()

    private val dbManager = SerializedTestDatabaseManager(db)

    private val wordlists = GeneratorWordlistRepositoryImpl(
        databaseManager = dbManager,
        dispatcher = Dispatchers.Unconfined,
    )

    private val words = GeneratorWordlistWordRepositoryImpl(
        databaseManager = dbManager,
        dispatcher = Dispatchers.Unconfined,
    )

    private val editWordlistWords = EditWordlistWordsImpl(
        generatorWordlistRepository = wordlists,
        generatorWordlistWordRepository = words,
    )

    @Test
    fun `adding words skips the ones the wordlist holds`() = runTest {
        val id = newWordlist("cat", "dog")

        edit(id, add = listOf("dog", "ant", "ant"))

        assertEquals(listOf("ant", "cat", "dog"), wordsOf(id))
        assertEquals(3L, wordlists.get().first().single().wordCount)
    }

    @Test
    fun `adding only words the wordlist holds is refused`() = runTest {
        val id = newWordlist("cat", "dog")

        assertEquals(WordlistWordError.EXISTS, errorOf(id, add = listOf("cat")))
        assertEquals(WordlistWordError.NO_NEW_WORDS, errorOf(id, add = listOf("cat", "dog")))
        assertEquals(listOf("cat", "dog"), wordsOf(id))
    }

    @Test
    fun `editing a word replaces it`() = runTest {
        val id = newWordlist("cat", "dog")

        edit(id, remove = setOf("cat"), add = listOf("cow"))

        assertEquals(listOf("cow", "dog"), wordsOf(id))
    }

    @Test
    fun `editing a word into another word of the wordlist is refused`() = runTest {
        val id = newWordlist("cat", "dog")

        assertEquals(WordlistWordError.EXISTS, errorOf(id, remove = setOf("cat"), add = listOf("dog")))
        assertEquals(listOf("cat", "dog"), wordsOf(id))
    }

    @Test
    fun `editing a word into itself changes nothing`() = runTest {
        val id = newWordlist("cat", "dog")
        db.generatorWordlistQueries.updateWordsHash(wordsHash = "hash", id = id)

        edit(id, remove = setOf("cat"), add = listOf("cat"))

        assertEquals(listOf("cat", "dog"), wordsOf(id))
        // Nothing was written.
        assertEquals("hash", db.generatorWordlistQueries.getById(id).executeAsOne().wordsHash)
    }

    @Test
    fun `deleting a word removes every copy of it`() = runTest {
        val id = newWordlist("cat", "cat", "dog")

        edit(id, remove = setOf("cat"))

        assertEquals(listOf("dog"), wordsOf(id))
    }

    @Test
    fun `an invalid word is refused`() = runTest {
        val id = newWordlist("cat")
        val tooLong = "a".repeat(ReadWordlistUtil.MAX_WORD_LENGTH + 1)

        assertEquals(WordlistWordError.BLANK, errorOf(id, add = listOf(" ")))
        assertEquals(WordlistWordError.TOO_LONG, errorOf(id, add = listOf("dog", tooLong)))
        assertEquals(WordlistWordError.LINE_BREAK, errorOf(id, add = listOf("d\nog")))
        assertEquals(WordlistWordError.BLANK, errorOf(id))
        assertEquals(listOf("cat"), wordsOf(id))
    }

    @Test
    fun `changing the words drops the cached id of their blob`() = runTest {
        val id = newWordlist("cat")
        db.generatorWordlistQueries.updateWordsHash(wordsHash = "hash", id = id)

        edit(id, add = listOf("dog"))

        assertNull(db.generatorWordlistQueries.getById(id).executeAsOne().wordsHash)
    }

    private suspend fun newWordlist(vararg words: String): Long {
        wordlists.post("Words", words.toList()).bind()
        return wordlists.get().first().single().idRaw
    }

    private suspend fun wordsOf(id: Long): List<String> = words.getWords(id).first()

    private suspend fun edit(
        id: Long,
        remove: Set<String> = emptySet(),
        add: List<String> = emptyList(),
    ) {
        val request = EditWordlistWordsRequest(
            wordlistId = id,
            remove = remove,
            add = add,
        )
        editWordlistWords(request).bind()
    }

    private suspend fun errorOf(
        id: Long,
        remove: Set<String> = emptySet(),
        add: List<String> = emptyList(),
    ): WordlistWordError = assertFailsWith<WordlistWordException> {
        edit(id, remove, add)
    }.error
}
