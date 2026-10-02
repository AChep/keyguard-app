package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.backup.BackupConfig
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.service.file.FileAccessToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BackupSetupSessionTest {
    @Test
    fun `two wizards retain independent drafts credentials grants and completion`() = runTest {
        val initial = BackupConfig(
            store = BackupStoreConfig.Local(path = "file:///initial", accessToken = FileAccessToken("initial grant")),
            password = Password("initial password"),
        )
        val first = editor(initial)
        val second = editor(initial)
        first.session.setPassword("replacement")
        first.session.restorePassword()
        first.session.setLocalDirectory("file:///first", "first grant")
        first.session.setIncludeAttachments(false)
        first.session.setRetention(7)
        second.session.setPassword("second password")
        second.session.setStoreKind("webdav")
        second.session.setWebDav("https://example.com/backups/", "second user", "server password")
        first.session.submit()
        runCurrent()
        assertEquals(1, first.completions)
        assertEquals(0, second.completions)
        assertEquals(Password("initial password"), first.saved.single().password)
        assertEquals(
            FileAccessToken("first grant"),
            assertIs<BackupStoreConfig.Local>(first.saved.single().store).accessToken,
        )
        assertFalse(first.saved.single().includeAttachments)
        assertEquals(7, first.saved.single().retention.maxSnapshots)
        // Late actions/completions on the first editor cannot mutate or save the second.
        first.session.setPassword("late password")
        first.session.submit()
        second.session.submit()
        runCurrent()
        assertEquals(1, first.saved.size)
        assertEquals(1, second.completions)
        assertEquals(Password("second password"), second.saved.single().password)
        val store = assertIs<BackupStoreConfig.WebDav>(second.saved.single().store)
        assertEquals("second user", store.username)
        assertEquals(Password("server password"), store.password)
        assertTrue(second.saved.single().includeAttachments)
    }

    @Test
    fun `closing during noncancellable verification never saves or completes another wizard`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val first = editor(verify = { withContext(NonCancellable) { gate.await() } })
        val second = editor()
        first.session.submit()
        runCurrent()
        assertTrue(first.frames.last().isTestingLocation)
        first.session.close()
        val frameCount = first.frames.size
        second.session.setRetention(12)
        second.session.submit()
        gate.complete(Unit)
        runCurrent()
        assertTrue(first.saved.isEmpty())
        assertEquals(0, first.completions)
        assertEquals(frameCount, first.frames.size)
        assertEquals(12, second.saved.single().retention.maxSnapshots)
        assertEquals(1, second.completions)
    }

    @Test
    fun `failed verification is scoped to its editor and permits retry`() = runTest {
        var shouldFail = true
        val first = editor(verify = { if (shouldFail) error("offline") })
        val second = editor()
        first.session.submit()
        runCurrent()
        assertEquals("offline", first.frames.last().error)
        assertFalse(first.frames.last().isTestingLocation)
        assertNull(second.frames.last().error)
        assertTrue(first.saved.isEmpty())
        assertEquals(0, first.completions)
        shouldFail = false
        first.session.setRetention(14)
        assertNull(first.frames.last().error)
        first.session.submit()
        runCurrent()
        assertEquals(14, first.saved.single().retention.maxSnapshots)
        assertEquals(1, first.completions)
        second.session.close()
    }

    @Test
    fun `folder callbacks cannot replace webdav or a config being verified`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val wizard = editor(verify = { gate.await() })
        wizard.session.setStoreKind("webdav")
        wizard.session.setWebDav("https://example.com/backups/", "user", "password")
        wizard.session.setLocalDirectory("file:///late", "late grant")
        assertEquals("webdav", wizard.frames.last().storeKind)
        wizard.session.submit()
        wizard.session.setStoreKind("local")
        wizard.session.setLocalDirectory("file:///during-save", "late grant")
        wizard.session.submit()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertIs<BackupStoreConfig.WebDav>(wizard.saved.single().store)
        assertEquals(1, wizard.completions)
    }

    private fun TestScope.editor(
        initial: BackupConfig = BackupConfig(store = BackupStoreConfig.Local(path = "file:///backups")),
        verify: suspend (BackupConfig) -> Unit = {},
    ) = EditorHarness(this, StandardTestDispatcher(testScheduler), initial, verify)

    private class EditorHarness(
        scope: CoroutineScope,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        initial: BackupConfig,
        verify: suspend (BackupConfig) -> Unit,
    ) {
        val frames = mutableListOf<BackupSetupSnapshot>()
        val saved = mutableListOf<BackupConfig>()
        var completions = 0
        val session = BackupSetupSession { publish, complete ->
            lateinit var editor: BackupSetupEditor
            editor = BackupSetupEditor(
                initial = initial,
                scope = scope,
                verify = verify,
                save = { saved += it },
                invalidDestinationMessage = "invalid destination",
                publish = { publish(it, editor) },
                complete = complete,
                storageDispatcher = dispatcher,
            )
            editor.publish()
            KeyguardCancellable(editor::close)
        }.also { it.observe({ frames += it }, { completions += 1 }) }
    }
}
