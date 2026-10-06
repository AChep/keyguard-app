package com.artemchep.keyguard.feature.remotepicker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RemotePickerStateFlowTest {
    @Test
    fun `sorts folders first and names case insensitively`() {
        val entries = listOf(
            entry("z.kdbx"),
            entry("beta", isFolder = true),
            entry("Alpha", isFolder = true),
            entry("A.kdbx"),
        )

        assertEquals(
            listOf("Alpha", "beta", "A.kdbx", "z.kdbx"),
            sortRemotePickerEntries(entries).map { it.name },
        )
    }

    @Test
    fun `case-sensitive storages accept names that differ only in case`() {
        assertNull(
            validateRemotePickerFileName("Vault.kdbx", listOf("vault.kdbx"), ignoreCase = false),
        )
        assertEquals(
            RemotePickerState.FileNameError.AlreadyExists,
            validateRemotePickerFileName("vault.kdbx", listOf("vault.kdbx"), ignoreCase = false),
        )
    }

    @Test
    fun `confirming a folder passes the current path`() {
        var result: String? = null

        val onConfirm = assertNotNull(
            confirm(path = "a/b", mode = RemotePickerMode.SelectFolder, onFolder = { result = it }),
        )
        onConfirm()

        assertEquals("a/b", result)
        assertNull(confirm(path = "a", loaded = false, mode = RemotePickerMode.SelectFolder))
    }

    @Test
    fun `creating a database joins the file name to the current path`() {
        var result: String? = null

        confirm(
            path = "a",
            mode = RemotePickerMode.CreateKeePassDatabase,
            fileName = " vault.kdbx ",
            onFile = { result = it },
        )?.invoke()

        assertEquals("a/vault.kdbx", result)
        assertNull(
            confirm(
                mode = RemotePickerMode.CreateKeePassDatabase,
                fileName = "vault.txt",
                fileNameError = RemotePickerState.FileNameError.ExtensionRequired,
            ),
        )
        assertNull(confirm(mode = RemotePickerMode.OpenKeePassDatabase))
    }

    private fun confirm(
        path: String = "",
        loaded: Boolean = true,
        mode: RemotePickerMode,
        fileName: String = "",
        fileNameError: RemotePickerState.FileNameError? = null,
        onFolder: (String) -> Unit = {},
        onFile: (String) -> Unit = {},
    ) = remotePickerOnConfirm(
        path = path,
        loaded = loaded,
        mode = mode,
        fileName = fileName,
        fileNameError = fileNameError,
        onFolder = onFolder,
        onFile = onFile,
    )

    private fun entry(
        name: String,
        isFolder: Boolean = false,
    ) = RemotePickerEntry(
        path = name,
        name = name,
        isFolder = isFolder,
        size = null,
    )
}
