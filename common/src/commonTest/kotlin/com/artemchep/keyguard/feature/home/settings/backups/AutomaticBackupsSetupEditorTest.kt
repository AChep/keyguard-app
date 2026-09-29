package com.artemchep.keyguard.feature.home.settings.backups

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.backup.BackupConfig
import com.artemchep.keyguard.common.service.backup.BackupRetention
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.service.backup.BackupStoreKind
import com.artemchep.keyguard.common.service.file.FileAccessToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutomaticBackupsSetupEditorTest {
    @Test
    fun `setup validates each step and saves only from review`() = runTest {
        val fixture = Fixture(this)
        val editor = fixture.editor
        assertEquals(BackupConfig(), editor.state.value.config)
        assertFalse(editor.state.value.canContinue)
        editor.next()
        assertEquals(BackupSetupStep.Destination, editor.state.value.step)
        fixture.chooseFolder()
        editor.next()
        assertEquals(BackupSetupStep.Protection, editor.state.value.step)
        assertTrue(editor.state.value.canContinue)
        editor.setPassword(" secret ")
        editor.setConfirmationPassword("secret")
        editor.next()
        assertEquals(BackupSetupStep.Protection, editor.state.value.step)
        editor.setConfirmationPassword(" secret ")
        editor.next()
        editor.setIncludeAttachments(false)
        editor.setRetention(90)
        editor.next()
        assertEquals(BackupSetupStep.Review, editor.state.value.step)
        assertTrue(fixture.saved.isEmpty())
        editor.next()
        advanceUntilIdle()
        val config = fixture.saved.single()
        assertEquals(listOf(config), fixture.verified)
        assertTrue(config.enabled)
        assertEquals(Password(" secret "), config.password)
        assertFalse(config.includeAttachments)
        assertEquals(BackupRetention(90), config.retention)
        assertEquals(fixture.folder, config.store)
        assertEquals(1, fixture.closed)
    }

    @Test
    fun `back preserves edits and exiting requires discard confirmation`() = runTest {
        val fixture = Fixture(this)
        val editor = fixture.editor
        fixture.chooseFolder()
        editor.next()
        editor.setPassword("password")
        editor.setConfirmationPassword("password")
        editor.next()
        editor.setRetention(5)
        editor.back()
        assertEquals(BackupSetupStep.Protection, editor.state.value.step)
        assertTrue(editor.state.value.passwordMatches)
        editor.back()
        editor.back()
        assertTrue(editor.state.value.confirmingDiscard)
        assertEquals(0, fixture.closed)
        editor.confirmDiscard(false)
        assertFalse(editor.state.value.confirmingDiscard)
        assertEquals(BackupRetention(5), editor.state.value.config.retention)
        editor.back()
        editor.confirmDiscard(true)
        assertEquals(1, fixture.closed)
        assertTrue(fixture.saved.isEmpty())
        editor.next()
        assertEquals(BackupSetupStep.Destination, editor.state.value.step)
    }

    @Test
    fun `untouched setup closes without confirmation and cancelled picker makes no edits`() = runTest {
        val fixture = Fixture(this)
        fixture.editor.selectStore(BackupStoreKind.Local)
        val receive = fixture.editor.destinationReceiver()
        receive(null)
        receive(fixture.folder)
        assertEquals(BackupConfig(), fixture.editor.state.value.config)
        fixture.editor.back()
        assertFalse(fixture.editor.state.value.confirmingDiscard)
        assertEquals(1, fixture.closed)
        assertTrue(fixture.saved.isEmpty())
    }

    @Test
    fun `destination drafts retain credentials and folder access across switches`() = runTest {
        val fixture = Fixture(this)
        fixture.chooseFolder()
        fixture.editor.selectStore(BackupStoreKind.WebDav)
        val webDav = BackupStoreConfig.WebDav("https://example.com/backups", "user", Password("secret"))
        fixture.editor.destinationReceiver()(webDav)
        fixture.editor.selectStore(BackupStoreKind.Local)
        assertEquals(fixture.folder, fixture.editor.state.value.config.store)
        fixture.editor.selectStore(BackupStoreKind.WebDav)
        assertEquals(webDav, fixture.editor.state.value.config.store)
        fixture.editor.destinationReceiver()(null)
        assertEquals(webDav, fixture.editor.state.value.config.store)
    }

    @Test
    fun `stale picker results cannot replace a different destination or newer selection`() = runTest {
        val fixture = Fixture(this)
        val stale = fixture.editor.destinationReceiver()
        fixture.editor.selectStore(BackupStoreKind.WebDav)
        fixture.editor.selectStore(BackupStoreKind.Local)
        stale(fixture.folder)
        assertEquals(BackupStoreConfig.Local(), fixture.editor.state.value.config.store)
        val first = fixture.editor.destinationReceiver()
        val second = fixture.editor.destinationReceiver()
        second(fixture.folder)
        first(BackupStoreConfig.Local("file:///old"))
        assertEquals(fixture.folder, fixture.editor.state.value.config.store)
    }

    @Test
    fun `picker callbacks after discard or vault lock are ignored`() = runTest {
        val fixture = Fixture(this)
        fixture.editor.setIncludeAttachments(false)
        val receive = fixture.editor.destinationReceiver()
        fixture.editor.back()
        fixture.editor.confirmDiscard(true)
        receive(fixture.folder)
        assertEquals(BackupStoreConfig.Local(), fixture.editor.state.value.config.store)
        val locked = Fixture(this)
        val lockedReceive = locked.editor.destinationReceiver()
        locked.job.cancel()
        lockedReceive(locked.folder)
        assertEquals(BackupConfig(), locked.editor.state.value.config)
    }

    @Test
    fun `verification failure preserves the draft and retry succeeds`() = runTest {
        val fixture = Fixture(this)
        fixture.verify = { error("Unavailable") }
        fixture.review()
        fixture.editor.next()
        advanceUntilIdle()
        assertEquals("Unavailable", fixture.editor.state.value.error)
        assertEquals(BackupSetupStep.Review, fixture.editor.state.value.step)
        assertTrue(fixture.editor.state.value.canContinue)
        assertTrue(fixture.saved.isEmpty())
        assertEquals(0, fixture.closed)
        fixture.verify = {}
        fixture.editor.next()
        assertNull(fixture.editor.state.value.error)
        advanceUntilIdle()
        assertEquals(1, fixture.saved.size)
        assertEquals(1, fixture.closed)
    }

    @Test
    fun `persistence failure remains on review and edits clear the error`() = runTest {
        val fixture = Fixture(this)
        fixture.save = { error("Write failed") }
        fixture.review()
        fixture.editor.next()
        advanceUntilIdle()
        assertEquals("Write failed", fixture.editor.state.value.error)
        assertEquals(0, fixture.closed)
        assertTrue(fixture.saved.isEmpty())
        fixture.save = {}
        fixture.editor.back()
        fixture.editor.setRetention(60)
        assertNull(fixture.editor.state.value.error)
        fixture.editor.next()
        fixture.editor.next()
        advanceUntilIdle()
        assertEquals(BackupRetention(60), fixture.saved.single().retention)
        assertEquals(1, fixture.closed)
    }

    @Test
    fun `verification blocks duplicate saves edits back and late picker results`() = runTest {
        val fixture = Fixture(this)
        val pending = CompletableDeferred<Unit>()
        fixture.verify = { pending.await() }
        fixture.chooseFolder()
        val receive = fixture.editor.destinationReceiver()
        fixture.review()
        val expected = fixture.editor.state.value.config
        fixture.editor.next()
        fixture.editor.next()
        fixture.editor.back()
        fixture.editor.setPassword("late change")
        fixture.editor.setRetention(5)
        fixture.editor.selectStore(BackupStoreKind.WebDav)
        receive(BackupStoreConfig.Local("file:///late"))
        assertTrue(fixture.editor.state.value.isSaving)
        assertFalse(fixture.editor.state.value.canContinue)
        assertEquals(expected, fixture.editor.state.value.config)
        assertEquals(BackupSetupStep.Review, fixture.editor.state.value.step)
        runCurrent()
        assertTrue(fixture.saved.isEmpty())
        pending.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, fixture.saved.size)
        assertEquals(1, fixture.closed)
        fixture.editor.next()
        advanceUntilIdle()
        assertEquals(1, fixture.saved.size)
    }

    @Test
    fun `cancelling the screen during verification never saves or navigates`() = runTest {
        val fixture = Fixture(this)
        val pending = CompletableDeferred<Unit>()
        fixture.verify = { pending.await() }
        fixture.review()
        fixture.editor.next()
        runCurrent()
        fixture.job.cancel()
        pending.complete(Unit)
        advanceUntilIdle()
        assertTrue(fixture.saved.isEmpty())
        assertEquals(0, fixture.closed)
    }

    @Test
    fun `custom retention and settings outside wizard survive setup`() = runTest {
        val fixture = Fixture(this, initial = BackupConfig(retention = BackupRetention(42), intervalMs = 1234))
        fixture.review()
        fixture.editor.next()
        advanceUntilIdle()
        assertEquals(BackupRetention(42), fixture.saved.single().retention)
        assertEquals(1234L, fixture.saved.single().intervalMs)
    }

    @Test
    fun `clearing password allows unencrypted setup despite old confirmation`() = runTest {
        val fixture = Fixture(this)
        fixture.chooseFolder()
        fixture.editor.next()
        fixture.editor.setPassword("secret")
        fixture.editor.setConfirmationPassword("different")
        assertFalse(fixture.editor.state.value.canContinue)
        fixture.editor.setPassword("")
        assertTrue(fixture.editor.state.value.canContinue)
        assertNull(fixture.editor.state.value.config.password)
    }

    private class Fixture(testScope: TestScope, initial: BackupConfig = BackupConfig()) {
        val job = SupervisorJob(testScope.backgroundScope.coroutineContext[Job])
        val folder = BackupStoreConfig.Local("file:///backups", FileAccessToken("bookmark"))
        val verified = mutableListOf<BackupConfig>()
        val saved = mutableListOf<BackupConfig>()
        var closed = 0
        var verify: suspend (BackupConfig) -> Unit = {}
        var save: suspend (BackupConfig) -> Unit = {}
        val editor = AutomaticBackupsSetupEditor(
            initial = initial,
            scope = CoroutineScope(testScope.coroutineContext + job),
            verify = { verify(it); verified += it },
            save = { save(it); saved += it },
            onClose = { closed += 1 },
            workerDispatcher = StandardTestDispatcher(testScope.testScheduler),
        )

        fun chooseFolder() = editor.destinationReceiver()(folder)

        fun review() {
            if (!editor.state.value.destinationValid) chooseFolder()
            repeat(3) { editor.next() }
            assertEquals(BackupSetupStep.Review, editor.state.value.step)
        }
    }
}
