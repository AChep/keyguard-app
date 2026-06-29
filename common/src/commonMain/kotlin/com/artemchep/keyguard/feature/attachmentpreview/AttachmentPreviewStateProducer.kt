package com.artemchep.keyguard.feature.attachmentpreview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.artemchep.keyguard.common.service.download.DownloadInfoEntity
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AttachmentPreviewException
import com.artemchep.keyguard.common.model.AttachmentPreviewKind
import com.artemchep.keyguard.common.model.AttachmentPreviewPayload
import com.artemchep.keyguard.common.model.AttachmentPreviewPolicy
import com.artemchep.keyguard.common.model.AttachmentPreviewRequest
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.attachmentPreviewKindByFileName
import com.artemchep.keyguard.common.model.isMarkdownAttachmentPreview
import com.artemchep.keyguard.common.service.download.DownloadManager
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.usecase.CanPreviewAttachment
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.common.usecase.GetAttachmentPreview
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.ui.theme.isDark
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun produceAttachmentPreviewState(
    args: AttachmentPreviewRoute.Args,
): Loadable<AttachmentPreviewState> = with(localDI().direct) {
    produceAttachmentPreviewState(
        args = args,
        canPreviewAttachment = instance(),
        getAttachmentPreview = instance(),
        downloadManager = instance(),
    )
}

@Composable
fun produceAttachmentPreviewState(
    args: AttachmentPreviewRoute.Args,
    canPreviewAttachment: CanPreviewAttachment,
    getAttachmentPreview: GetAttachmentPreview,
    downloadManager: DownloadManager,
): Loadable<AttachmentPreviewState> = produceScreenState(
    key = "attachment_preview:${args.localCipherId}:${args.attachmentId}",
    initial = Loadable.Loading,
    args = arrayOf(
        args,
        canPreviewAttachment,
        getAttachmentPreview,
        downloadManager,
    ),
) {
    val producerScope = this
    val copyText = copier()
    val state = createAttachmentPreviewState(
        args = args,
        canPreviewAttachment = canPreviewAttachment,
        getAttachmentPreview = getAttachmentPreview,
        downloadManager = downloadManager,
        copyText = copyText,
    )
    val content = state.content as? AttachmentPreviewContent.TextLike
    if (content?.code?.canSyntaxHighlight() != true) {
        flowOf(Loadable.Ok(state))
    } else {
        flow {
            emit(Loadable.Ok(state))

            snapshotFlow { producerScope.colorScheme.isDark }
                .distinctUntilChanged()
                .collect { isDark ->
                    val highlightedCode = runCatching {
                        content.code.withSyntaxHighlighting(isDark = isDark)
                    }.getOrNull()
                    if (highlightedCode != null) {
                        emit(
                            Loadable.Ok(
                                state.withCode(highlightedCode),
                            ),
                        )
                    }
                }
        }
    }
}

internal suspend fun createAttachmentPreviewState(
    args: AttachmentPreviewRoute.Args,
    canPreviewAttachment: CanPreviewAttachment,
    getAttachmentPreview: GetAttachmentPreview,
    downloadManager: DownloadManager,
    copyText: CopyText,
): AttachmentPreviewState {
    canPreviewAttachment(
        fileName = args.fileName,
        encryptedSize = args.encryptedSize,
    ).toPreviewErrorOrNull()?.let { error ->
        return AttachmentPreviewState(
            fileName = args.fileName,
            content = AttachmentPreviewContent.Error(error),
        )
    }

    val localUrl = cachedAttachmentPreviewLocalUrl(
        args = args,
        downloadManager = downloadManager,
    )
    val request = AttachmentPreviewRequest(
        localCipherId = args.localCipherId,
        remoteCipherId = args.remoteCipherId,
        attachmentId = args.attachmentId,
        fileName = args.fileName,
        encryptedSize = args.encryptedSize,
        localUrl = localUrl,
    )
    val result = getAttachmentPreview(request)
        .attempt()
        .bind()

    var fileName = args.fileName
    val content = result.fold(
        ifLeft = { e ->
            AttachmentPreviewContent.Error(
                type = e.toPreviewError(),
            )
        },
        ifRight = { payload ->
            fileName = payload.fileName
            payload.toPreviewContent(copyText = copyText)
        },
    )
    val state = AttachmentPreviewState(
        fileName = fileName,
        content = content,
    )
    return state
}

internal suspend fun cachedAttachmentPreviewLocalUrl(
    args: AttachmentPreviewRoute.Args,
    downloadManager: DownloadManager,
): String? {
    val tag = DownloadInfoEntity.AttachmentDownloadTag(
        localCipherId = args.localCipherId,
        remoteCipherId = args.remoteCipherId,
        attachmentId = args.attachmentId,
    )
    val status = downloadManager
        .statusByTag(tag)
        .first()

    return when (status) {
        is DownloadProgress.Complete -> status.result.fold(
            ifLeft = { null },
            ifRight = { it },
        )

        is DownloadProgress.Loading,
        DownloadProgress.None -> null
    }
}

private fun Throwable.toPreviewError(): AttachmentPreviewError = when (this) {
    is AttachmentPreviewException.TooLarge -> AttachmentPreviewError.TooLarge
    is AttachmentPreviewException.NetworkFailed -> AttachmentPreviewError.Network
    is AttachmentPreviewException.DecryptionFailed -> AttachmentPreviewError.Decryption
    else -> AttachmentPreviewError.Unknown
}

internal fun AttachmentPreviewPolicy.toPreviewErrorOrNull(): AttachmentPreviewError? = when (this) {
    AttachmentPreviewPolicy.Previewable -> null
    is AttachmentPreviewPolicy.TooLarge -> AttachmentPreviewError.TooLarge
    AttachmentPreviewPolicy.UnsupportedType -> AttachmentPreviewError.UnsupportedFileType
    AttachmentPreviewPolicy.UnsupportedPlatform -> AttachmentPreviewError.UnsupportedPlatform
}

private fun AttachmentPreviewPayload.toPreviewContent(
    copyText: CopyText,
): AttachmentPreviewContent {
    return when (attachmentPreviewKindByFileName(fileName)) {
        AttachmentPreviewKind.Image -> AttachmentPreviewContent.Image(bytes = bytes)
        AttachmentPreviewKind.Markdown -> decodeTextPreview(
            fileName = fileName,
            bytes = bytes,
            copyText = copyText,
        )
        AttachmentPreviewKind.Text -> decodeTextPreview(
            fileName = fileName,
            bytes = bytes,
            copyText = copyText,
        )

        null -> AttachmentPreviewContent.Error(AttachmentPreviewError.UnsupportedFileType)
    }
}

internal fun decodeTextPreview(
    fileName: String,
    bytes: ByteArray,
    copyText: CopyText,
): AttachmentPreviewContent {
    val sampleSize = minOf(bytes.size, 4096)
    for (i in 0 until sampleSize) {
        if (bytes[i] == 0.toByte()) {
            return AttachmentPreviewContent.Error(AttachmentPreviewError.TextDecode)
        }
    }

    val offset = if (
        bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() &&
        bytes[1] == 0xBB.toByte() &&
        bytes[2] == 0xBF.toByte()
    ) {
        3
    } else {
        0
    }
    val text = bytes
        .decodeToString(
            startIndex = offset,
            endIndex = bytes.size,
            throwOnInvalidSequence = false,
        )
    val code = AttachmentPreviewCode(
        text = text,
        lineIndex = AttachmentPreviewLineIndex.of(text),
        syntaxLanguage = attachmentPreviewSyntaxLanguageByFileName(fileName),
        annotatedString = AnnotatedString(text),
    )
    val onCopy = {
        copyText.copy(
            text = text,
            hidden = true,
            type = CopyText.Type.VALUE,
        )
    }
    return if (isMarkdownAttachmentPreview(fileName)) {
        AttachmentPreviewContent.Markdown(
            code = code,
            onCopy = onCopy,
        )
    } else {
        AttachmentPreviewContent.Text(
            code = code,
            onCopy = onCopy,
        )
    }
}

private fun AttachmentPreviewState.withCode(
    code: AttachmentPreviewCode,
): AttachmentPreviewState = copy(
    content = when (val content = content) {
        is AttachmentPreviewContent.Text -> content.copy(code = code)
        is AttachmentPreviewContent.Markdown -> content.copy(code = code)
        is AttachmentPreviewContent.Image,
        is AttachmentPreviewContent.Error -> content
    },
)

private fun AttachmentPreviewCode.canSyntaxHighlight(): Boolean =
    syntaxLanguage != null && text.length <= SYNTAX_HIGHLIGHT_MAX_CHARS

private fun AttachmentPreviewCode.withSyntaxHighlighting(
    isDark: Boolean,
): AttachmentPreviewCode {
    val syntaxLanguage = syntaxLanguage ?: return this
    return copy(
        annotatedString = highlightPreviewText(
            text = text,
            syntaxLanguage = syntaxLanguage,
            isDark = isDark,
        ),
    )
}

private fun highlightPreviewText(
    text: String,
    syntaxLanguage: SyntaxLanguage,
    isDark: Boolean,
): AnnotatedString {
    val codeHighlights = Highlights.Builder()
        .code(text)
        .theme(SyntaxThemes.darcula(darkMode = isDark))
        .language(syntaxLanguage)
        .build()

    return buildAnnotatedString {
        append(text)

        codeHighlights.getHighlights()
            .filterIsInstance<ColorHighlight>()
            .forEach {
                val start = it.location.start.coerceIn(0, text.length)
                val end = it.location.end.coerceIn(start, text.length)
                if (start < end) {
                    addStyle(
                        SpanStyle(color = Color(it.rgb).copy(alpha = 1f)),
                        start = start,
                        end = end,
                    )
                }
            }
        codeHighlights.getHighlights()
            .filterIsInstance<BoldHighlight>()
            .forEach {
                val start = it.location.start.coerceIn(0, text.length)
                val end = it.location.end.coerceIn(start, text.length)
                if (start < end) {
                    addStyle(
                        SpanStyle(fontWeight = FontWeight.Bold),
                        start = start,
                        end = end,
                    )
                }
            }
    }
}

private const val SYNTAX_HIGHLIGHT_MAX_CHARS = 250_000
