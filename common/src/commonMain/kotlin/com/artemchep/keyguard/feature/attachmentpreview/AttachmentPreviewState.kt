package com.artemchep.keyguard.feature.attachmentpreview

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import dev.snipme.highlights.model.SyntaxLanguage

@Immutable
data class AttachmentPreviewState(
    val fileName: String,
    val content: AttachmentPreviewContent,
)

@Immutable
data class AttachmentPreviewCode(
    val text: String,
    val lineIndex: AttachmentPreviewLineIndex,
    val syntaxLanguage: SyntaxLanguage?,
    val annotatedString: AnnotatedString,
)

sealed interface AttachmentPreviewContent {
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
        val onCopy: () -> Unit
    }

    data class Text(
        override val code: AttachmentPreviewCode,
        override val onCopy: () -> Unit,
    ) : TextLike

    data class Markdown(
        override val code: AttachmentPreviewCode,
        override val onCopy: () -> Unit,
    ) : TextLike

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
    Unknown,
}
