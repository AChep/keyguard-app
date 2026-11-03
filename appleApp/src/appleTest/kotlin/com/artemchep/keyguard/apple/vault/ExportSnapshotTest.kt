package com.artemchep.keyguard.apple.vault

import com.artemchep.keyguard.common.service.permission.PermissionState
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.export.ExportState
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExportSnapshotTest {
    @Test
    fun confirmedPasswordCanExport() = runTest {
        val (snapshot, handlers) = input(
            password = "secret",
            confirmation = "secret",
            onExport = {},
        ).toSnapshot("Export") { (it as TextHolder.Value).data }

        assertTrue(snapshot.passwordValid)
        assertTrue(snapshot.canExport)
        assertTrue("export:run" in handlers)
        assertNull(snapshot.confirmationPasswordError)
    }

    @Test
    fun passwordMismatchShowsTheErrorAndCanNotExport() = runTest {
        val (snapshot, handlers) = input(
            password = "secret",
            confirmation = "different",
            confirmationError = "The passwords do not match.",
        ).toSnapshot("Export") { (it as TextHolder.Value).data }

        assertFalse(snapshot.passwordValid)
        assertFalse(snapshot.canExport)
        assertFalse("export:run" in handlers)
        assertEquals("The passwords do not match.", snapshot.confirmationPasswordError)
        assertEquals("Confirm archive password", snapshot.confirmationPasswordLabel)
    }

    @Test
    fun formatOptionsKeepTheirActionIds() = runTest {
        val selected = mutableListOf<String>()
        val (snapshot, handlers) = input(
            onFormat = { selected += it },
        ).toSnapshot("Export") { (it as TextHolder.Value).data }

        assertEquals(listOf("export:format:zip", "export:format:kdbx"), snapshot.formats.map { it.id })
        assertEquals("ZIP archive", snapshot.formatTitle)
        assertEquals("Attachments note", snapshot.attachmentsNote)
        handlers.getValue("export:format:kdbx").invoke()
        assertEquals(listOf("kdbx"), selected)
    }

    @Test
    fun aRunningExportShowsProgressAndCanNotStartAgain() {
        val snapshot = ExportSnapshot.empty
            .copy(canExport = true)
            .withRun(
                ExportExecutionState(
                    running = true,
                    exportId = "export",
                    downloaded = 25,
                    total = 100,
                ),
            )

        assertFalse(snapshot.canExport)
        assertTrue(snapshot.running)
        assertTrue(snapshot.canCancel)
        assertEquals(0.25, snapshot.progress)
    }

    private fun input(
        password: String = "",
        confirmation: String = "",
        confirmationError: String? = null,
        onExport: (() -> Unit)? = null,
        onFormat: (String) -> Unit = {},
    ) = ExportFormInput(
        items = ExportState.Items(
            revision = 0,
            list = emptyList(),
            count = 3,
        ),
        attachments = ExportState.Attachments(
            revision = 0,
            list = emptyList(),
            count = 0,
            enabled = false,
        ),
        filter = ExportState.Filter(
            items = emptyList(),
        ),
        format = ExportState.Format(
            title = "ZIP archive",
            note = "Note",
            attachmentsNote = "Attachments note",
            passwordLabel = "Archive password",
            confirmPasswordLabel = "Confirm archive password",
            options = listOf("zip", "kdbx").map { id ->
                FlatItemAction(
                    id = id,
                    title = TextHolder.Value(id),
                    selected = id == "zip",
                    onClick = { onFormat(id) },
                )
            },
        ),
        password = ExportState.Password(
            model = TextFieldModel(text = password),
        ),
        confirmation = ExportState.Password(
            model = TextFieldModel(text = confirmation, error = confirmationError),
        ),
        content = ExportState.Content(
            writePermission = PermissionState.Granted,
            onExportClick = onExport,
        ),
    )
}
