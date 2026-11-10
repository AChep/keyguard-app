package com.artemchep.keyguard.feature.filepicker

import kotlin.test.Test
import kotlin.test.assertEquals

class DroppedFileCopiesTest {
    private val deleted = mutableListOf<String>()
    private val copies = DroppedFileCopies { deleted += it }

    @Test
    fun `release deletes a held file only`() {
        copies.hold("a")
        copies.release("b")
        assertEquals(emptyList(), deleted)

        copies.release("a")
        copies.release("a")
        assertEquals(listOf("a"), deleted)
    }

    @Test
    fun `sync releases the files that left the set`() {
        copies.sync(setOf("a", "b"))
        copies.sync(setOf("b", "c"))
        assertEquals(listOf("a"), deleted)
    }

    @Test
    fun `a stale sync keeps an explicitly held file`() {
        copies.sync(setOf("a"))
        copies.hold("b")
        copies.sync(setOf("a"))
        copies.sync(emptySet())
        assertEquals(listOf("a"), deleted)
    }

    @Test
    fun `discard deletes a file the screen does not hold`() {
        copies.sync(setOf("a"))
        copies.discard("a")
        copies.discard("b")
        assertEquals(listOf("b"), deleted)
    }

    @Test
    fun `a handed off file is never deleted`() {
        copies.hold("a")
        copies.handOff("a")
        copies.hold("a")
        copies.sync(setOf("a"))
        copies.close()
        assertEquals(emptyList(), deleted)
    }

    @Test
    fun `a lease defers deletions until it ends`() {
        copies.hold("a")
        copies.hold("b")
        val releaseFirst = copies.lease()
        val releaseSecond = copies.lease()
        copies.release("a")
        copies.close()
        assertEquals(emptyList(), deleted)

        releaseFirst()
        releaseFirst()
        assertEquals(emptyList(), deleted)

        releaseSecond()
        assertEquals(setOf("a", "b"), deleted.toSet())
    }

    @Test
    fun `a file held again while a lease waits is kept`() {
        copies.sync(setOf("a"))
        val release = copies.lease()
        copies.sync(emptySet())
        copies.sync(setOf("a"))
        release()
        assertEquals(emptyList(), deleted)
    }

    @Test
    fun `close deletes every held file, and any file held after it`() {
        copies.hold("a")
        copies.sync(setOf("b"))
        copies.close()
        assertEquals(setOf("a", "b"), deleted.toSet())

        copies.hold("c")
        copies.sync(setOf("d"))
        assertEquals(setOf("a", "b", "c", "d"), deleted.toSet())
    }
}
