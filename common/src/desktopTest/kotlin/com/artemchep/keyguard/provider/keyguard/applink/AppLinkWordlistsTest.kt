package com.artemchep.keyguard.provider.keyguard.applink

import app.cash.sqldelight.Query
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.session.BlobExchange
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wordlists between linked apps, with the envelopes and blobs handed over by hand. */
// One function per scenario.
@Suppress("TooManyFunctions")
class AppLinkWordlistsTest {

    @Test
    fun `a wordlist that does not fit the words a pass writes waits for the next pass`() = runTest {
        val colors = listOf("red", "green", "blue")
        val a = AppLinkTestDevice("A")
        val b = AppLinkTestDevice("B", maxWordsPerPass = 4)
        a.link()
        b.time = a.time + 1
        b.join(a)
        a.addWordlist("Animals", animals)
        a.addWordlist("Colors", colors)
        a.exchangeWith(b)
        listOf(animals, colors).forEach { words ->
            b.components.blobs.onReceived("A", a.hashOf(words), WordlistBlob.encode(words))
        }

        // Each list fits a pass, both do not: one waits.
        assertTrue(b.receive(a))
        assertEquals(1, b.wordlistWords().size)
        assertFalse(b.materialize())
        assertEquals(
            mapOf("Animals" to animals.sorted(), "Colors" to colors.sorted()),
            b.wordlistWords(),
        )
    }

    @Test
    fun `a wordlist a pass deferred is written by the next one from words held here`() = runTest {
        val a = AppLinkTestDevice("A")
        val b = AppLinkTestDevice("B", maxWordsPerPass = animals.size)
        a.link()
        b.time = a.time + 1
        b.join(a)
        b.addWordlist("Mine", animals)
        a.addWordlist("First", animals)
        a.addWordlist("Second", animals)

        // B holds the words already, but each list takes a whole pass: one waits.
        assertTrue(b.receive(a))
        assertEquals(2, b.wordlistList().size)
        assertFalse(b.materialize())
        assertEquals(
            mapOf("First" to animals.sorted(), "Mine" to animals.sorted(), "Second" to animals.sorted()),
            b.wordlistWords().mapValues { it.value.sorted() },
        )
        // No words were sent.
        assertTrue(b.components.receivedBlobs.hashes().isEmpty())
        assertTrue(b.wanted().isEmpty())
    }

    @Test
    fun `an empty wordlist a full pass deferred is written by the next one`() = runTest {
        val a = AppLinkTestDevice("A")
        val b = AppLinkTestDevice("B", maxWordsPerPass = animals.size)
        a.link()
        b.time = a.time + 1
        b.join(a)
        a.addWordlist("Animals", animals)
        a.addWordlist("Empty", emptyList())
        b.components.blobs.onReceived("A", a.hashOf(animals), WordlistBlob.encode(animals))

        // The animals fill the pass before the empty list comes.
        val empty = a.wordlistSyncId("Empty")
        val envelope = a.envelope().let { it.copy(entries = it.entries.sortedBy { entry -> entry.id == empty }) }
        assertTrue(b.receive(a, envelope))
        assertEquals(listOf("Animals"), b.wordlistList().map { it.name })
        assertFalse(b.materialize())
        assertEquals(mapOf("Animals" to animals.sorted(), "Empty" to emptyList()), b.wordlistWords())
    }

    private val animals = listOf("cat", "dog", "ant")

    @Test
    fun `starting a link publishes the wordlists with the id of their words`() = runTest {
        val a = AppLinkTestDevice("A")
        a.addWordlist("Animals", animals)

        a.link()

        val entry = a.entries().values.single()
        assertEquals(EntryKind.WORDLIST, entry.knownKind)
        assertEquals(a.wordlistSyncId("Animals"), entry.id)
        assertEquals(a.hashOf(animals), entry.wordsHash())
        assertEquals(setOf(a.hashOf(animals)), a.components.blobs.held())
    }

    @Test
    fun `a wordlist shows up once its words arrive`() = runTest {
        val a = AppLinkTestDevice("A")
        val b = AppLinkTestDevice("B")
        a.link()
        b.time = a.time + 1
        b.join(a)
        a.addWordlist("Animals", animals)
        a.exchangeWith(b)

        // The entry is here, the words are not: no row yet.
        assertTrue(b.wordlistList().isEmpty())
        val hash = a.hashOf(animals)
        assertEquals(setOf(hash), b.components.receivedBlobs.hashes() + b.wanted())

        // The words arrive; the next pass writes the wordlist.
        val bytes = WordlistBlob.encode(animals)
        b.components.blobs.onReceived("A", hash, bytes)
        b.receive(a)

        assertEquals(mapOf("Animals" to animals.sorted()), b.wordlistWords())
        assertEquals(a.wordlistSyncId("Animals"), b.wordlistSyncId("Animals"))
        assertTrue(b.components.receivedBlobs.hashes().isEmpty())
        assertTrue(b.wanted().isEmpty())
        // The device that received the words hands them over too.
        assertEquals(bytes.toList(), b.components.blobs.read(hash)?.toList())
    }

    @Test
    fun `a received wordlist is visible in one complete database update`() = runTest {
        val words = List(20_000) { "word${it.toString().padStart(5, '0')}" }
        AppLinkTestDevice("A").use { a ->
            AppLinkTestDevice("B").use { b ->
                a.link()
                b.time = a.time + 1
                b.join(a)
                a.addWordlist("Large", words)
                a.exchangeWith(b)
                b.components.blobs.onReceived("A", a.hashOf(words), WordlistBlob.encode(words))

                val query = b.db.generatorWordlistQueries.get()
                var updates = 0
                var firstWordCounts: List<Long>? = null
                val listener = Query.Listener {
                    updates += 1
                    if (firstWordCounts == null) {
                        firstWordCounts = query.executeAsList().map { it.wordCount }
                    }
                }
                query.addListener(listener)
                try {
                    b.materialize()
                } finally {
                    query.removeListener(listener)
                }

                assertEquals(listOf(words.size.toLong()), firstWordCounts)
                assertEquals(1, updates)
                assertEquals(mapOf("Large" to words), b.wordlistWords())
            }
        }
    }

    @Test
    fun `linking identical wordlists adopts one id and keeps the local rows`() = runTest {
        val a = AppLinkTestDevice("A")
        val b = AppLinkTestDevice("B")
        a.addWordlist("Animals", animals)
        b.addWordlist("Animals", animals.reversed())
        val before = b.wordlist("Animals").idRaw
        val oldSyncId = b.wordlistSyncId("Animals")

        a.link()
        b.time = a.time + 1
        b.join(a)
        a.exchangeWith(b)

        assertEquals(1, a.wordlistList().size)
        assertEquals(1, b.wordlistList().size)
        assertEquals(a.wordlistSyncId("Animals"), b.wordlistSyncId("Animals"))
        assertNotEquals(oldSyncId, b.wordlistSyncId("Animals"))
        assertEquals(before, b.wordlist("Animals").idRaw)
    }

    @Test
    fun `renaming a wordlist whose new words are not here yet keeps the new words`() = runTest {
        val birds = listOf("owl", "crow")
        val a = AppLinkTestDevice("A")
        val b = AppLinkTestDevice("B")
        a.link()
        b.time = a.time + 1
        b.join(a)
        a.addWordlist("Animals", animals)
        a.exchangeWith(b)
        b.components.blobs.onReceived("A", a.hashOf(animals), WordlistBlob.encode(animals))
        b.receive(a)

        // A newer build of A gives the list other words; B does not have them yet.
        a.time += 10_000
        a.replaceWords("Animals", birds)
        b.time = a.time
        b.receive(a)
        assertEquals(mapOf("Animals" to animals.sorted()), b.wordlistWords())

        b.time += 10_000
        b.renameWordlist("Animals", "Pets")

        // B wrote the name alone: the words stay the ones A wrote.
        val version = b.entries().values.single { it.knownKind == EntryKind.WORDLIST }.versions.single()
        assertEquals(a.hashOf(birds), (version.body["words"] as JsonPrimitive).content)
        assertEquals("Pets", (version.body["name"] as JsonPrimitive).content)
        assertEquals(b.deviceId, version.parts.getValue("/name").node)
        assertEquals(a.deviceId, version.parts.getValue("/words").node)
    }

    @Test
    fun `a received blob that is not the words of a wordlist is refused`() = runTest {
        val a = AppLinkTestDevice("A")
        val bytes = "dog\ncat".encodeToByteArray()
        val hash = BlobExchange.hashOf(a.crypto, bytes)

        a.components.blobs.onReceived("B", hash, bytes)

        assertTrue(a.components.receivedBlobs.hashes().isEmpty())
        assertNull(a.components.blobs.read(hash))
    }

    private fun SyncEntry.wordsHash(): String? = (body?.get("words") as? JsonPrimitive)?.content

    /** Gives the wordlist [name] the [words], as a build that edits the words would. */
    private suspend fun AppLinkTestDevice.replaceWords(name: String, words: List<String>) {
        val id = wordlist(name).idRaw
        val syncId = wordlistSyncId(name)
        dbManager.mutate("test") { database ->
            components.recorder.record(database, EntryKind.WORDLIST, ids = { listOf(syncId) }) {
                database.generatorWordlistWordQueries.deleteByWordlistId(id)
                words.forEach { database.generatorWordlistWordQueries.insert(wordlistId = id, word = it) }
                // Taken again from the words.
                database.generatorWordlistQueries.updateWordsHash(wordsHash = null, id = id)
            }
        }.bind()
    }

    private fun AppLinkTestDevice.hashOf(words: List<String>): String =
        BlobExchange.hashOf(crypto, WordlistBlob.encode(words))

    /** The blobs the device needs, as the last sync pass found. */
    private fun AppLinkTestDevice.wanted(): Set<String> {
        // Claiming as a peer that holds every blob reveals what is wanted.
        val blobs = components.blobs
        val peer = "probe"
        val wanted = mutableSetOf<String>()
        blobs.onPeerBlobs(peer, entries().values.mapNotNull { it.wordsHash() })
        while (true) {
            val hash = blobs.claimNext(peer) ?: break
            wanted += hash
        }
        blobs.onSessionEnded(peer)
        return wanted
    }
}
