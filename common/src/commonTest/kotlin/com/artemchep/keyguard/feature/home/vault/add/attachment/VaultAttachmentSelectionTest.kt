package com.artemchep.keyguard.feature.home.vault.add.attachment

import com.artemchep.keyguard.common.model.KEEPASS_FILE_UPLOAD_MAX_BYTES
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountSI
import com.artemchep.keyguard.feature.fileupload.BITWARDEN_FILE_UPLOAD_MAX_BYTES
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.platform.leParseUri
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultAttachmentSelectionTest {
    @Test
    fun `accepted attachment without name falls back to file`() {
        val result = filePickerResult(
            size = 2048L,
            name = null,
        ).toVaultAttachmentSelectionResult(AccountType.BITWARDEN)

        val success = assertIs<VaultAttachmentSelectionResult.Success>(result)
        assertEquals("File", success.attachment.name)
        assertEquals("content://attachment/file", success.attachment.identity.uri.toString())
        assertEquals(2048L, success.attachment.identity.size)
        assertEquals("2.0 kB", success.attachment.size)
    }

    @Test
    fun `oversized bitwarden attachment shows error and is not added`() = runTest {
        assertOversizedAttachmentRejected(
            accountType = AccountType.BITWARDEN,
            size = BITWARDEN_FILE_UPLOAD_MAX_BYTES + 1L,
        )
    }

    @Test
    fun `oversized keepass attachment shows error and is not added`() = runTest {
        assertOversizedAttachmentRejected(
            accountType = AccountType.KEEPASS,
            size = KEEPASS_FILE_UPLOAD_MAX_BYTES + 1L,
        )
    }

    @Test
    fun `oversized attachment uses bitwarden limit when account is unknown`() {
        val result = filePickerResult(BITWARDEN_FILE_UPLOAD_MAX_BYTES + 1L)
            .toVaultAttachmentSelectionResult(accountType = null)

        val fileTooLarge = assertIs<VaultAttachmentSelectionResult.FileTooLarge>(result)
        assertEquals(BITWARDEN_FILE_UPLOAD_MAX_BYTES, fileTooLarge.maximumBytes)
    }

    @Test
    fun `attachment exactly at upload limit is added without an error`() = runTest {
        val messages = mutableListOf<ToastMessage>()
        val attachments = mutableListOf<SkeletonAttachment.Local>()

        handleVaultAttachmentSelection(
            result = filePickerResult(size = BITWARDEN_FILE_UPLOAD_MAX_BYTES),
            accountType = AccountType.BITWARDEN,
            uploadLimitErrorTitle = { UPLOAD_LIMIT_ERROR },
            showMessage = messages::add,
            addAttachment = attachments::add,
        )

        assertTrue(messages.isEmpty())
        assertEquals(1, attachments.size)
        assertEquals(BITWARDEN_FILE_UPLOAD_MAX_BYTES, attachments.single().identity.size)
    }

    @Test
    fun `attachment with unknown size is added without an error`() = runTest {
        val messages = mutableListOf<ToastMessage>()
        val attachments = mutableListOf<SkeletonAttachment.Local>()

        handleVaultAttachmentSelection(
            result = filePickerResult(size = null),
            accountType = AccountType.BITWARDEN,
            uploadLimitErrorTitle = { UPLOAD_LIMIT_ERROR },
            showMessage = messages::add,
            addAttachment = attachments::add,
        )

        assertTrue(messages.isEmpty())
        assertEquals(1, attachments.size)
        assertNull(attachments.single().identity.size)
    }

    private suspend fun assertOversizedAttachmentRejected(
        accountType: AccountType,
        size: Long,
    ) {
        val expectedMaximumBytes = when (accountType) {
            AccountType.BITWARDEN -> BITWARDEN_FILE_UPLOAD_MAX_BYTES
            AccountType.KEEPASS -> KEEPASS_FILE_UPLOAD_MAX_BYTES
        }
        val result = filePickerResult(size)
            .toVaultAttachmentSelectionResult(accountType)
        val fileTooLarge = assertIs<VaultAttachmentSelectionResult.FileTooLarge>(result)
        assertEquals(expectedMaximumBytes, fileTooLarge.maximumBytes)

        val messages = mutableListOf<ToastMessage>()
        val attachments = mutableListOf<SkeletonAttachment.Local>()
        var resolvedMaximumBytes: Long? = null

        handleVaultAttachmentSelection(
            result = filePickerResult(size = size),
            accountType = accountType,
            uploadLimitErrorTitle = { maximumBytes ->
                resolvedMaximumBytes = maximumBytes
                "File must be ${humanReadableByteCountSI(maximumBytes)} or smaller"
            },
            showMessage = messages::add,
            addAttachment = attachments::add,
        )

        assertTrue(attachments.isEmpty())
        assertEquals(expectedMaximumBytes, resolvedMaximumBytes)
        assertEquals(1, messages.size)
        assertEquals(ToastMessage.Type.ERROR, messages.single().type)
        assertEquals(UPLOAD_LIMIT_ERROR, messages.single().title)
    }

    private fun filePickerResult(
        size: Long?,
        name: String? = "attachment.bin",
    ) = FilePickerResult(
        uri = leParseUri("content://attachment/file"),
        name = name,
        size = size,
    )

    private companion object {
        const val UPLOAD_LIMIT_ERROR = "File must be 524.3 MB or smaller"
    }
}
