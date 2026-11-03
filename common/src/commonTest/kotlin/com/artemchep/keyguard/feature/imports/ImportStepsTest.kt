package com.artemchep.keyguard.feature.imports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ImportStepsTest {
    private val released = mutableListOf<Held>()

    private val file = Held.FileCopy("file:///tmp/export.json")
    private val other = Held.FileCopy("file:///tmp/other.json")

    @Test
    fun `a move releases what only the old step held`() {
        val steps = ImportSteps(Step.Loading(setOf(file, other)), released::add)

        assertTrue(steps.move(steps.value, Step.Loading(setOf(file))))
        assertEquals(listOf<Held>(other), released)
    }

    @Test
    fun `a file held by both steps survives the move`() {
        val prompt = Step.Loading(setOf(Held.FileCopy("file:///tmp/export.json")))
        val steps = ImportSteps(prompt, released::add)

        assertTrue(steps.move(prompt, Step.Loading(setOf(file))))
        assertTrue(released.isEmpty())
    }

    @Test
    fun `a lost move releases only what the new step brought along`() {
        val stale = Step.Loading(setOf(file))
        val current = Step.Loading(setOf(file))
        val steps = ImportSteps(current, released::add)

        assertFalse(steps.move(stale, Step.Loading(setOf(file, other))))
        assertSame(current, steps.value)
        assertEquals(listOf<Held>(other), released)
    }

    @Test
    fun `a claim hands over what it took instead of releasing it`() {
        val loading = Step.Loading(setOf(file))
        val steps = ImportSteps(loading, released::add)

        val lease = assertNotNull(steps.claim(loading, Step.Start))
        steps.close()
        assertTrue(released.isEmpty())

        lease.release()
        assertEquals(listOf<Held>(file), released)
        assertNull(lease.source)
    }

    @Test
    fun `closing releases whatever the current step holds`() {
        val steps = ImportSteps(Step.Loading(setOf(file)), released::add)

        steps.close()
        assertEquals(Step.Start, steps.value)
        assertEquals(listOf<Held>(file), released)
    }
}
