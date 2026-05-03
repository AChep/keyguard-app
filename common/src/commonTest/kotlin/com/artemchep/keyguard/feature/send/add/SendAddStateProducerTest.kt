package com.artemchep.keyguard.feature.send.add

import androidx.compose.runtime.mutableStateOf
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.common.model.create.CreateSendRequest
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.feature.fileupload.BITWARDEN_FILE_UPLOAD_MAX_BYTES
import com.artemchep.keyguard.platform.leParseUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class SendAddStateProducerTest {
    @Test
    fun `existing file send can still be saved without a local file`() {
        val canSave = canSaveFileSend(
            output = CreateSendRequest(
                type = DSend.Type.File,
                now = TEST_INSTANT,
            ),
            initialValue = createExistingFileSend(),
        )

        assertTrue(canSave)
    }

    @Test
    fun `new file send requires a selected file and non blank name`() {
        assertFalse(
            canSaveFileSend(
                output = CreateSendRequest(
                    type = DSend.Type.File,
                    file = CreateSendRequest.File(
                        name = "invoice.pdf",
                    ),
                    now = TEST_INSTANT,
                ),
                initialValue = null,
            ),
        )

        assertFalse(
            canSaveFileSend(
                output = CreateSendRequest(
                    type = DSend.Type.File,
                    file = CreateSendRequest.File(
                        uri = "content://send/file",
                        name = "   ",
                    ),
                    now = TEST_INSTANT,
                ),
                initialValue = null,
            ),
        )

        assertTrue(
            canSaveFileSend(
                output = CreateSendRequest(
                    type = DSend.Type.File,
                    file = CreateSendRequest.File(
                        uri = "content://send/file",
                        name = "invoice.pdf",
                    ),
                    now = TEST_INSTANT,
                ),
                initialValue = null,
            ),
        )
    }

    @Test
    fun `existing file attachment config is read only`() {
        val config = existingSendFileAttachmentConfig(
            file = DSend.File(
                id = "file-1",
                fileName = "invoice.pdf",
                keyBase64 = "file-key",
                sizeName = "12 KB",
            ),
        )

        assertEquals("file-1", config.id)
        assertEquals("12 KB", config.size)
        assertTrue(config.synced)
        assertFalse(config.editable)
    }

    @Test
    fun `selected file seeds attachment fields without prefilling send title`() {
        val args = SendAddRoute.Args(
            type = DSend.Type.File,
            selectedFile = FilePickerResult(
                uri = leParseUri("content://send/file"),
                name = "invoice.pdf",
                size = 2048L,
            ),
        )

        val file = selectedFileToCreateSendFile(args.selectedFile)

        assertNull(args.name)
        assertEquals("content://send/file", file.uri)
        assertEquals("invoice.pdf", file.name)
        assertEquals(2048L, file.size)
    }

    @Test
    fun `selected file without picker name seeds blank send attachment name`() {
        val file = selectedFileToCreateSendFile(
            selectedFile = FilePickerResult(
                uri = leParseUri("content://send/file"),
                name = null,
                size = 2048L,
            ),
        )

        assertEquals("content://send/file", file.uri)
        assertEquals("", file.name)
        assertEquals(2048L, file.size)
    }

    @Test
    fun `selected file over bitwarden upload limit is ignored`() {
        val file = selectedFileToCreateSendFile(
            selectedFile = FilePickerResult(
                uri = leParseUri("content://send/file"),
                name = "large.bin",
                size = BITWARDEN_FILE_UPLOAD_MAX_BYTES + 1L,
            ),
        )

        assertNull(file.uri)
        assertNull(file.name)
        assertNull(file.size)
    }

    @Test
    fun `apply selected send file updates attachment name sink`() {
        val uriSink = MutableStateFlow<String?>(null)
        val nameSink = MutableStateFlow("")
        val nameState = mutableStateOf("")
        val sizeSink = MutableStateFlow<Long?>(null)

        applySelectedSendFile(
            result = FilePickerResult(
                uri = leParseUri("content://send/file"),
                name = "invoice.pdf",
                size = 2048L,
            ),
            uriSink = uriSink,
            nameSink = nameSink,
            nameState = nameState,
            sizeSink = sizeSink,
        )

        assertEquals("content://send/file", uriSink.value)
        assertEquals("invoice.pdf", nameSink.value)
        assertEquals("invoice.pdf", nameState.value)
        assertEquals(2048L, sizeSink.value)
    }

    @Test
    fun `apply selected send file over bitwarden upload limit keeps existing selection`() {
        val uriSink = MutableStateFlow<String?>("content://send/old")
        val nameSink = MutableStateFlow("old.txt")
        val nameState = mutableStateOf("old.txt")
        val sizeSink = MutableStateFlow<Long?>(128L)

        val applied = applySelectedSendFile(
            result = FilePickerResult(
                uri = leParseUri("content://send/large"),
                name = "large.bin",
                size = BITWARDEN_FILE_UPLOAD_MAX_BYTES + 1L,
            ),
            uriSink = uriSink,
            nameSink = nameSink,
            nameState = nameState,
            sizeSink = sizeSink,
        )

        assertFalse(applied)
        assertEquals("content://send/old", uriSink.value)
        assertEquals("old.txt", nameSink.value)
        assertEquals("old.txt", nameState.value)
        assertEquals(128L, sizeSink.value)
    }

    @Test
    fun `apply selected send file replaces existing local selection`() {
        val uriSink = MutableStateFlow<String?>("content://send/old")
        val nameSink = MutableStateFlow("old.txt")
        val sizeSink = MutableStateFlow<Long?>(128L)

        applySelectedSendFile(
            result = FilePickerResult(
                uri = leParseUri("content://send/new"),
                name = "new.txt",
                size = 512L,
            ),
            uriSink = uriSink,
            nameSink = nameSink,
            sizeSink = sizeSink,
        )

        assertEquals("content://send/new", uriSink.value)
        assertEquals("new.txt", nameSink.value)
        assertEquals(512L, sizeSink.value)
    }

    @Test
    fun `apply selected send file without picker name uses blank attachment name`() {
        val uriSink = MutableStateFlow<String?>(null)
        val nameSink = MutableStateFlow("old.txt")
        val nameState = mutableStateOf("old.txt")
        val sizeSink = MutableStateFlow<Long?>(null)

        applySelectedSendFile(
            result = FilePickerResult(
                uri = leParseUri("content://send/file"),
                name = null,
                size = 2048L,
            ),
            uriSink = uriSink,
            nameSink = nameSink,
            nameState = nameState,
            sizeSink = sizeSink,
        )

        assertEquals("content://send/file", uriSink.value)
        assertEquals("", nameSink.value)
        assertEquals("", nameState.value)
        assertEquals(2048L, sizeSink.value)
    }

    @Test
    fun `with send file clears blank attachment name`() {
        val output = CreateSendRequest(
            now = TEST_INSTANT,
        ).withSendFile(
            uri = null,
            size = null,
            name = "   ",
        )

        assertNull(output.file.uri)
        assertNull(output.file.size)
        assertNull(output.file.name)
    }
}

private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")

private fun createExistingFileSend() = DSend(
    id = "send-1",
    accountId = "account-1",
    accessId = "access-1",
    keyBase64 = "send-key",
    revisionDate = TEST_INSTANT,
    createdDate = TEST_INSTANT,
    deletedDate = null,
    expirationDate = null,
    service = BitwardenService(),
    authType = DSend.AuthType.None,
    name = "Quarterly report",
    notes = "",
    accessCount = 0,
    hasPassword = false,
    synced = true,
    disabled = false,
    hideEmail = false,
    emails = emptyList(),
    type = DSend.Type.File,
    text = null,
    file = DSend.File(
        id = "file-1",
        fileName = "invoice.pdf",
        keyBase64 = "file-key",
    ),
)
