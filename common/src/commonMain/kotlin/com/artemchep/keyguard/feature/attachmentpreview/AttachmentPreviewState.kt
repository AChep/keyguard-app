package com.artemchep.keyguard.feature.attachmentpreview

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.ui.Breadcrumb
import dev.snipme.highlights.model.SyntaxLanguage

@Immutable
data class AttachmentPreviewState(
    val fileName: String,
    val content: AttachmentPreviewContent,
    val onBack: (() -> Unit)? = null,
    val onSave: (() -> Unit)? = null,
    val busy: Boolean = false,
    val onCancel: (() -> Unit)? = null,
    val password: AttachmentArchivePassword? = null,
    val export: AttachmentPreviewExport? = null,
) {
    /** Back cancels a running archive operation before it leaves the folder. */
    val onNavigateBack: (() -> Unit)?
        get() = onCancel ?: onBack
}

@Immutable
data class AttachmentPreviewCode(
    val text: String,
    val lineIndex: AttachmentPreviewLineIndex,
    val syntaxLanguage: SyntaxLanguage?,
    val annotatedString: AnnotatedString,
)

sealed interface AttachmentPreviewContent {
    /** Copies the whole file as text, or `null` if there is no text. */
    val onCopy: (() -> Unit)?
        get() = null

    data class Archive(
        internal val source: com.artemchep.keyguard.util.zip.ZipArchive,
        val path: String = "",
        val breadcrumbs: List<Breadcrumb> = emptyList(),
        val items: List<AttachmentArchiveItem> = emptyList(),
    ) : AttachmentPreviewContent

    data class Image(
        val bytes: ByteArray,
    ) : AttachmentPreviewContent

    sealed interface TextLike : AttachmentPreviewContent {
        val code: AttachmentPreviewCode
        val text: String
            get() = code.text
        val lineIndex: AttachmentPreviewLineIndex
            get() = code.lineIndex
        val syntaxLanguage: SyntaxLanguage?
            get() = code.syntaxLanguage
        override val onCopy: () -> Unit
    }

    data class Text(
        override val code: AttachmentPreviewCode,
        override val onCopy: () -> Unit,
    ) : TextLike

    data class Markdown(
        override val code: AttachmentPreviewCode,
        override val onCopy: () -> Unit,
    ) : TextLike

    data class Table(
        override val code: AttachmentPreviewCode,
        val table: AttachmentPreviewTable,
        override val onCopy: () -> Unit,
    ) : TextLike

    /**
     * Parsed OpenPGP certificates. A binary key has
     * no [source] to show or copy.
     */
    data class GpgKey(
        val items: List<VaultViewItem>,
        val source: AttachmentPreviewCode?,
        override val onCopy: (() -> Unit)?,
    ) : AttachmentPreviewContent

    data class Error(
        val type: AttachmentPreviewError,
    ) : AttachmentPreviewContent
}

enum class AttachmentPreviewError {
    UnsupportedFileType,
    UnsupportedPlatform,
    TooLarge,
    Network,
    Decryption,
    TextDecode,
    GpgKey,
    ArchiveInvalid,
    ArchiveUnsupported,
    ArchivePassword,
    ArchiveTooManyEntries,
    ArchiveExportTooLarge,
    ArchiveExportFailed,
    Unknown,
}


data class AttachmentArchiveItem(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long?,
    val isEncrypted: Boolean,
    val onOpen: (() -> Unit)?,
    val onSave: (() -> Unit)?,
)

data class AttachmentArchivePassword(
    val incorrect: Boolean,
    val onSubmit: (String) -> Unit,
    val onCancel: () -> Unit,
)
