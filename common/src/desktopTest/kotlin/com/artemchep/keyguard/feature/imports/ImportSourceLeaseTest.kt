package com.artemchep.keyguard.feature.imports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ImportSourceLeaseTest {
    @Test
    fun `a claimed file outlives the steps`() = runTest {
        val fixture = ImportFlowTestSupport(backgroundScope)
        val prompt = fixture.filePrompt()
        fixture.steps.set(prompt)
        fixture.sources.confirmPassword(prompt)
        runCurrent()
        fixture.requests.single().complete(fixture.attachmentResult)
        runCurrent()
        val review = assertIs<Step.Review>(fixture.steps.value)

        val lease = assertNotNull(fixture.steps.claimCommit(review)).lease
        assertNotNull(lease.source)
        // What the screen does as it closes mid-commit.
        fixture.steps.close()
        assertTrue(fixture.deletedFiles.isEmpty())
        assertNull(fixture.steps.claimCommit(review))

        lease.release()
        assertEquals(listOf(fixture.uriOf(prompt)), fixture.deletedFiles)
    }

    @Test
    fun `a file without attachments leaves nothing to claim`() = runTest {
        val fixture = ImportFlowTestSupport(backgroundScope)
        val prompt = fixture.filePrompt()
        fixture.steps.set(prompt)
        fixture.sources.confirmPassword(prompt)
        runCurrent()
        fixture.requests.single().complete(fixture.result)
        runCurrent()
        val review = assertIs<Step.Review>(fixture.steps.value)
        assertEquals(listOf(fixture.uriOf(prompt)), fixture.deletedFiles)

        val lease = assertNotNull(fixture.steps.claimCommit(review)).lease
        assertNull(lease.source)
        lease.release()
        assertEquals(listOf(fixture.uriOf(prompt)), fixture.deletedFiles)
    }

    @Test
    fun `a claimed backup outlives the steps`() = runTest {
        val fixture = ImportFlowTestSupport(backgroundScope)
        val prompt = fixture.backupPrompt()
        fixture.steps.set(prompt)
        fixture.sources.confirmPassword(prompt)
        runCurrent()
        fixture.sources.importSnapshot(assertIs<Step.Snapshots>(fixture.steps.value))
        runCurrent()
        fixture.requests.single().complete(fixture.attachmentResult)
        runCurrent()
        val review = assertIs<Step.Review>(fixture.steps.value)

        val lease = assertNotNull(fixture.steps.claimCommit(review)).lease
        // What the screen does as it closes mid-commit.
        fixture.steps.close()
        runCurrent()
        val backup = fixture.opened.single()
        assertFalse(backup.closed)
        assertNull(fixture.steps.claimCommit(review))

        lease.release()
        runCurrent()
        assertTrue(backup.closed)
    }

    @Test
    fun `an error that returns to the snapshots keeps the backup open`() = runTest {
        val fixture = ImportFlowTestSupport(backgroundScope)
        val prompt = fixture.backupPrompt()
        fixture.steps.set(prompt)
        fixture.sources.confirmPassword(prompt)
        runCurrent()
        val snapshots = assertIs<Step.Snapshots>(fixture.steps.value)
        fixture.sources.importSnapshot(snapshots)
        runCurrent()
        fixture.requests.single().complete(fixture.unreadable)
        runCurrent()
        val error = assertIs<Step.Error>(fixture.steps.value)
        assertEquals(Retry.Snapshots(snapshots), error.retry)
        runCurrent()
        val backup = fixture.opened.single()
        assertFalse(backup.closed)

        assertTrue(fixture.steps.move(error, snapshots))
        runCurrent()
        assertFalse(backup.closed)

        fixture.sources.cancel()
        runCurrent()
        assertTrue(backup.closed)
    }
}
