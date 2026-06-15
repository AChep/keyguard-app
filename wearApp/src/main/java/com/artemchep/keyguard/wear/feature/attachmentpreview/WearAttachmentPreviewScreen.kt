package com.artemchep.keyguard.wear.feature.attachmentpreview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewContent
import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewError
import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewRoute
import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewState
import com.artemchep.keyguard.feature.attachmentpreview.message
import com.artemchep.keyguard.feature.attachmentpreview.produceAttachmentPreviewState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.attachment_preview_error_decryption
import com.artemchep.keyguard.res.attachment_preview_error_image_decode
import com.artemchep.keyguard.res.attachment_preview_error_network
import com.artemchep.keyguard.res.attachment_preview_error_text_decode
import com.artemchep.keyguard.res.attachment_preview_error_too_large
import com.artemchep.keyguard.res.attachment_preview_error_unsupported_file
import com.artemchep.keyguard.res.attachment_preview_error_unsupported_platform
import com.artemchep.keyguard.res.error_failed_unknown
import com.artemchep.keyguard.ui.markdown.MarkdownText
import com.artemchep.keyguard.wear.ui.WearListLabel
import com.artemchep.keyguard.wear.ui.WearScaffoldLoader
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import com.artemchep.keyguard.wear.ui.surfaceTransformation
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearAttachmentPreviewScreen(
    args: AttachmentPreviewRoute.Args,
) {
    val loadableState = produceAttachmentPreviewState(args = args)
    WearAttachmentPreviewScaffold(
        fileName = args.fileName,
        loadableState = loadableState,
    )
}

@Composable
private fun WearAttachmentPreviewScaffold(
    fileName: String,
    loadableState: Loadable<AttachmentPreviewState>,
) {
    val title = loadableState.fold(
        ifLoading = { fileName },
        ifOk = { it.fileName },
    )
    val loading = loadableState is Loadable.Loading
    WearScaffoldScreen(
        title = title,
        overlay = {
            WearScaffoldLoader(
                visible = loading,
            )
        },
    ) { transformationSpec ->
        loadableState.fold(
            ifLoading = {
                // The scaffold overlay renders the loading indicator.
            },
            ifOk = { state ->
                when (val content = state.content) {
                    is AttachmentPreviewContent.Image -> {
                        item("image") {
                            WearAttachmentPreviewImage(
                                modifier = Modifier
                                    .transformedHeight(this, transformationSpec),
                                bytes = content.bytes,
                                fileName = state.fileName,
                                transformation = SurfaceTransformation(transformationSpec),
                            )
                        }
                    }

                    is AttachmentPreviewContent.Text -> {
                        item("text") {
                            WearAttachmentPreviewText(
                                modifier = Modifier
                                    .transformedHeight(this, transformationSpec),
                                text = content.text,
                                transformation = SurfaceTransformation(transformationSpec),
                            )
                        }
                    }

                    is AttachmentPreviewContent.Markdown -> {
                        item("markdown") {
                            WearAttachmentPreviewMarkdown(
                                modifier = Modifier
                                    .transformedHeight(this, transformationSpec),
                                text = content.text,
                                transformation = SurfaceTransformation(transformationSpec),
                            )
                        }
                    }

                    is AttachmentPreviewContent.Error -> {
                        item("error") {
                            WearListLabel(
                                modifier = Modifier
                                    .transformedHeight(this, transformationSpec),
                                text = content.type.message(),
                                error = true,
                                transformation = SurfaceTransformation(transformationSpec),
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun WearAttachmentPreviewImage(
    modifier: Modifier,
    bytes: ByteArray,
    fileName: String,
    transformation: SurfaceTransformation?,
) {
    val context = LocalPlatformContext.current
    val model = remember(context, bytes) {
        ImageRequest.Builder(context)
            .data(bytes)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .networkCachePolicy(CachePolicy.DISABLED)
            .build()
    }

    SubcomposeAsyncImage(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(MaterialTheme.shapes.medium)
            .surfaceTransformation(transformation),
        model = model,
        contentDescription = fileName,
        contentScale = ContentScale.Fit,
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        },
        success = {
            SubcomposeAsyncImageContent()
        },
        error = {
            WearListLabel(
                text = stringResource(Res.string.attachment_preview_error_image_decode),
                error = true,
            )
        },
    )
}

@Composable
private fun WearAttachmentPreviewText(
    modifier: Modifier,
    text: String,
    transformation: SurfaceTransformation?,
) {
    SelectionContainer {
        Text(
            modifier = modifier
                .fillMaxWidth()
                .surfaceTransformation(transformation)
                .padding(PaddingValues(horizontal = 12.dp, vertical = 8.dp)),
            text = text,
            fontFamily = FontFamily.Monospace,
            softWrap = true,
        )
    }
}

@Composable
private fun WearAttachmentPreviewMarkdown(
    modifier: Modifier,
    text: String,
    transformation: SurfaceTransformation?,
) {
    SelectionContainer {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .surfaceTransformation(transformation)
                .padding(PaddingValues(horizontal = 12.dp, vertical = 8.dp)),
        ) {
            MarkdownText(
                modifier = Modifier
                    .fillMaxWidth(),
                markdown = text,
            )
        }
    }
}
