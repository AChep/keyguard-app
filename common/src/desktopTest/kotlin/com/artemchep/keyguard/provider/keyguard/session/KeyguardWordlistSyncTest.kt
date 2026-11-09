package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DAppLinkSyncCategory
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wordlists between live sessions: the entry carries the name, and the words follow as a
 * blob from whichever device holds them.
 */
class KeyguardWordlistSyncTest {
    private val animals = listOf("cat", "dog", "ant", "émeu", "dog")

    /** The words other devices get: each word of [animals] once. */
    private val synced = animals.distinct().sorted()

    @Test
    fun `a wordlist arrives with its words, then follows renames and deletes`() = realTime {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val (a, b) = group.devices
            a.startLive()
            b.startLive()
            awaitConnected(a, b)

            a.addWordlist("Animals", animals)
            eventually { "Animals" in b.wordlists() }
            assertEquals(synced, b.wordlists().getValue("Animals"))

            // A rename keeps the row the generator refers to.
            val rowId = b.app.wordlist("Animals").idRaw
            a.renameWordlist("Animals", "Beasts")
            eventually { b.wordlists().keys == setOf("Beasts") }
            assertEquals(rowId, b.app.wordlist("Beasts").idRaw)
            assertEquals(synced, b.wordlists().getValue("Beasts"))

            b.removeWordlist("Beasts")
            eventually { a.wordlists().isEmpty() }
        }
    }

    @Test
    fun `the words come from any device that holds them`() = realTime {
        KeyguardSessionTestGroup(size = 3).use { group ->
            val (a, b, c) = group.devices
            val liveA = a.startLive()
            b.startLive()
            awaitConnected(a, b)

            a.addWordlist("Animals", animals)
            eventually { "Animals" in b.wordlists() }

            // The device that made the wordlist is gone: the one that received it hands it over.
            liveA.cancel()
            c.startLive()
            awaitConnected(b, c)
            eventually { "Animals" in c.wordlists() }
            assertEquals(synced, c.wordlists().getValue("Animals"))
        }
    }

    @Test
    fun `an empty wordlist needs no words to arrive`() = realTime {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val (a, b) = group.devices
            a.startLive()
            b.startLive()
            awaitConnected(a, b)

            a.addWordlist("Empty", emptyList())
            eventually { "Empty" in b.wordlists() }
            assertEquals(emptyList(), b.wordlists().getValue("Empty"))
        }
    }

    @Test
    fun `a device that does not sync wordlists neither fetches nor serves them`() = realTime {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val (a, b) = group.devices
            b.app.switches.set(DAppLinkSyncCategory.WORDLISTS, false).bind()
            b.addWordlist("Local", listOf("one", "two"))
            a.startLive()
            b.startLive()
            awaitConnected(a, b)

            a.addWordlist("Animals", animals)
            // The forwarder arrives after the wordlist would have.
            a.addForwarder("Mail")
            eventually { "Mail" in b.forwarders() }
            delay(1_000)

            assertEquals(setOf("Local"), b.wordlists().keys)
            assertEquals(setOf("Animals"), a.wordlists().keys)
            assertTrue(b.app.components.blobs.held().isEmpty())
        }
    }
}
