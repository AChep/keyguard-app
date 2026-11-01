package com.artemchep.keyguard.feature.imports

import com.artemchep.keyguard.common.service.imports.ImportError
import com.artemchep.keyguard.common.service.imports.ImportResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ImportFlowConcurrencyTest {
    @Test
    fun `file password confirmation can only start one parse`() = runTest {
        val fixture = ImportFlowTestSupport(backgroundScope)
        val prompt = fixture.filePrompt()
        fixture.steps.set(prompt)
        fixture.sources.confirmPassword(prompt)
        fixture.sources.confirmPassword(prompt)
        runCurrent()
        assertEquals(1, fixture.requests.size)

        fixture.requests.single().complete(fixture.result)
        runCurrent()
        assertIs<Step.Review>(fixture.steps.value)
        val done = Step.Done(0, 1)
        fixture.steps.set(done)
        fixture.sources.confirmPassword(prompt)
        runCurrent()
        assertSame(done, fixture.steps.value)
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun `wrong file password can be retried from the new prompt`() = runTest {
        val fixture = ImportFlowTestSupport(backgroundScope)
        val prompt = fixture.filePrompt()
        fixture.steps.set(prompt)
        fixture.sources.confirmPassword(prompt)
        runCurrent()
        fixture.requests.single().complete(ImportResult.Failure(ImportError.WrongPassword))
        runCurrent()
        val retry = assertIs<Step.Password>(fixture.steps.value)
        assertTrue(retry.wrong)
        assertTrue(fixture.deletedFiles.isEmpty())
        fixture.password.setText("corrected password")
        fixture.sources.confirmPassword(retry)
        runCurrent()
        assertEquals(2, fixture.requests.size)
        fixture.requests[1].complete(fixture.result)
        runCurrent()
        assertIs<Step.Review>(fixture.steps.value)
        assertEquals("", fixture.password.sink.value.text)
        assertEquals(listOf(fixture.uriOf(prompt)), fixture.deletedFiles)
    }

    @Test
    fun `released file parse cannot replace completed import`() = runTest {
        val fixture = ImportFlowTestSupport(backgroundScope)
        val prompt = fixture.filePrompt()
        fixture.steps.set(prompt)
        fixture.sources.confirmPassword(prompt)
        runCurrent()
        // Whatever replaces the loading step lets go of what it holds.
        val done = Step.Done(0, 1)
        fixture.steps.set(done)
        fixture.password.setText("new password")
        fixture.requests.single().complete(fixture.result)
        runCurrent()
        assertSame(done, fixture.steps.value)
        assertEquals("new password", fixture.password.sink.value.text)
        assertEquals(listOf(fixture.uriOf(prompt)), fixture.deletedFiles)
    }

    @Test
    fun `older file parse cannot replace a newer loading operation`() = runTest {
        val fixture = ImportFlowTestSupport(backgroundScope)
        val first = fixture.filePrompt("first.json")
        fixture.steps.set(first)
        fixture.sources.confirmPassword(first)
        runCurrent()
        val second = fixture.filePrompt("second.json")
        fixture.steps.set(second)
        fixture.sources.confirmPassword(second)
        runCurrent()
        val loading = assertIs<Step.Loading>(fixture.steps.value)
        fixture.password.setText("second password")
        fixture.requests[0].complete(fixture.result)
        runCurrent()
        assertSame(loading, fixture.steps.value)
        assertEquals("second password", fixture.password.sink.value.text)
        assertEquals(listOf(fixture.uriOf(first)), fixture.deletedFiles)
        fixture.requests[1].complete(fixture.result)
        runCurrent()
        assertIs<Step.Review>(fixture.steps.value)
        assertEquals(listOf(fixture.uriOf(first), fixture.uriOf(second)), fixture.deletedFiles)
    }

    @Test
    fun `backup password confirmation opens only once and ignores stale callbacks`() = runTest {
        val fixture = ImportFlowTestSupport(backgroundScope)
        val prompt = fixture.backupPrompt()
        fixture.steps.set(prompt)
        fixture.sources.confirmPassword(prompt)
        fixture.sources.confirmPassword(prompt)
        runCurrent()
        val snapshots = assertIs<Step.Snapshots>(fixture.steps.value)
        assertEquals(1, fixture.opened.size)
        fixture.sources.confirmPassword(prompt)
        runCurrent()
        assertSame(snapshots, fixture.steps.value)
        assertEquals(1, fixture.opened.size)
    }

    @Test
    fun `snapshot confirmation can only start one parse`() = runTest {
        val fixture = ImportFlowTestSupport(backgroundScope)
        val prompt = fixture.backupPrompt()
        fixture.steps.set(prompt)
        fixture.sources.confirmPassword(prompt)
        runCurrent()
        val snapshots = assertIs<Step.Snapshots>(fixture.steps.value)
        fixture.sources.importSnapshot(snapshots)
        fixture.sources.importSnapshot(snapshots)
        runCurrent()
        assertEquals(1, fixture.requests.size)
        fixture.requests.single().complete(fixture.result)
        runCurrent()
        assertIs<Step.Review>(fixture.steps.value)
        val done = Step.Done(0, 1)
        fixture.steps.set(done)
        fixture.sources.importSnapshot(snapshots)
        runCurrent()
        assertSame(done, fixture.steps.value)
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun `older snapshot parse cannot replace a newer parse from the same backup`() = runTest {
        val fixture = ImportFlowTestSupport(backgroundScope)
        val prompt = fixture.backupPrompt()
        fixture.steps.set(prompt)
        fixture.sources.confirmPassword(prompt)
        runCurrent()
        val snapshots = assertIs<Step.Snapshots>(fixture.steps.value)
        fixture.sources.importSnapshot(snapshots)
        runCurrent()
        fixture.steps.set(snapshots)
        fixture.sources.importSnapshot(snapshots)
        runCurrent()
        val loading = assertIs<Step.Loading>(fixture.steps.value)
        fixture.requests[0].complete(fixture.result)
        runCurrent()
        assertSame(loading, fixture.steps.value)
        fixture.requests[1].complete(fixture.result)
        runCurrent()
        assertIs<Step.Review>(fixture.steps.value)
    }

    @Test
    fun `released snapshot parse cannot replace completed import`() = runTest {
        val fixture = ImportFlowTestSupport(backgroundScope)
        val prompt = fixture.backupPrompt()
        fixture.steps.set(prompt)
        fixture.sources.confirmPassword(prompt)
        runCurrent()
        fixture.sources.importSnapshot(assertIs<Step.Snapshots>(fixture.steps.value))
        runCurrent()
        // Whatever replaces the loading step lets go of what it holds.
        val done = Step.Done(0, 1)
        fixture.steps.set(done)
        fixture.requests.single().complete(fixture.result)
        runCurrent()
        assertSame(done, fixture.steps.value)
        assertTrue(fixture.opened.single().closed)
    }
}
