package com.artemchep.keyguard.feature.attachmentpreview

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.WrapText
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.feature.attachmentpreview.minimap.AttachmentPreviewCodeMinimap
import com.artemchep.keyguard.feature.attachmentpreview.minimap.AttachmentPreviewMinimapMaxRowPitch
import com.artemchep.keyguard.feature.attachmentpreview.minimap.AttachmentPreviewMinimapMaxWidth
import com.artemchep.keyguard.feature.attachmentpreview.minimap.AttachmentPreviewMinimapMinWidth
import com.artemchep.keyguard.feature.attachmentpreview.minimap.AttachmentPreviewMinimapVisibleMinWidth
import com.artemchep.keyguard.feature.attachmentpreview.minimap.attachmentPreviewMinimapPanelHeightPx
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.Placeholder
import com.artemchep.keyguard.ui.markdown.MarkdownText
import com.artemchep.keyguard.ui.scaffoldContentWindowInsets
import com.artemchep.keyguard.ui.surface.LocalSurfaceColor
import com.artemchep.keyguard.ui.tabs.SegmentedButtonGroup
import com.artemchep.keyguard.ui.tabs.TabItem
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.LocalHazeBlurStyle
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max

@Composable
fun AttachmentPreviewScreen(
    args: AttachmentPreviewRoute.Args,
) {
    val loadableState = produceAttachmentPreviewState(args = args)
    AttachmentPreviewScaffold(
        fileName = args.fileName,
        loadableState = loadableState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentPreviewScaffold(
    fileName: String,
    loadableState: Loadable<AttachmentPreviewState>,
) {
    var wrapLines by rememberSaveable(fileName) {
        mutableStateOf(false)
    }
    var markdownModeKey by rememberSaveable(fileName) {
        mutableStateOf(AttachmentPreviewTextMode.Rendered.key)
    }
    val markdownMode = remember(markdownModeKey) {
        AttachmentPreviewTextMode.entries
            .firstOrNull { it.key == markdownModeKey }
            ?: AttachmentPreviewTextMode.Source
    }

    Scaffold(
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = loadableState.fold(
                            ifLoading = { fileName },
                            ifOk = { it.fileName },
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                actions = {
                    loadableState.fold(
                        ifLoading = {
                        },
                        ifOk = { state ->
                            val content = state.content as? AttachmentPreviewContent.TextLike
                            if (content != null) {
                                val copyAllText = stringResource(
                                    Res.string.attachment_preview_action_copy_all,
                                )
                                IconButton(
                                    onClick = content.onCopy,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ContentCopy,
                                        contentDescription = copyAllText,
                                    )
                                }

                                val wrapLinesText = stringResource(
                                    Res.string.attachment_preview_action_wrap_lines,
                                )
                                IconToggleButton(
                                    checked = wrapLines,
                                    onCheckedChange = {
                                        wrapLines = it
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.WrapText,
                                        contentDescription = wrapLinesText,
                                        tint = if (wrapLines) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            LocalContentColor.current
                                        },
                                    )
                                }
                            }
                        },
                    )
                },
            )
        },
        contentWindowInsets = scaffoldContentWindowInsets,
    ) { contentPadding ->
        loadableState.fold(
            ifLoading = {
                AttachmentPreviewLoading(
                    contentPadding = contentPadding,
                )
            },
            ifOk = { state ->
                when (val content = state.content) {
                    is AttachmentPreviewContent.Image -> AttachmentPreviewImage(
                        bytes = content.bytes,
                        fileName = state.fileName,
                        contentPadding = contentPadding,
                    )

                    is AttachmentPreviewContent.Text -> AttachmentPreviewText(
                        content = content,
                        wrapLines = wrapLines,
                        contentPadding = contentPadding,
                    )

                    is AttachmentPreviewContent.Markdown -> AttachmentPreviewMarkdown(
                        content = content,
                        markdownMode = markdownMode,
                        onMarkdownModeChange = {
                            markdownModeKey = it.key
                        },
                        wrapLines = wrapLines,
                        contentPadding = contentPadding,
                    )

                    is AttachmentPreviewContent.Error -> AttachmentPreviewErrorPane(
                        message = content.type.message(),
                        contentPadding = contentPadding,
                    )
                }
            },
        )
    }
}

@Composable
private fun AttachmentPreviewLoading(
    contentPadding: PaddingValues,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun AttachmentPreviewImage(
    bytes: ByteArray,
    fileName: String,
    contentPadding: PaddingValues,
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

    var scale by remember(bytes) { mutableFloatStateOf(1f) }
    var offset by remember(bytes) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember(bytes) { mutableStateOf(Size.Zero) }
    var imageSize by remember(bytes) { mutableStateOf(Size.Zero) }

    LaunchedEffect(scale, viewportSize, imageSize) {
        val clampedOffset = clampAttachmentPreviewImageOffset(
            offset = offset,
            viewportSize = viewportSize,
            imageSize = imageSize,
            scale = scale,
        )
        if (offset != clampedOffset) {
            offset = clampedOffset
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .clipToBounds()
            .onSizeChanged {
                viewportSize = Size(
                    width = it.width.toFloat(),
                    height = it.height.toFloat(),
                )
            }
            .pointerInput(bytes, viewportSize, imageSize) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    scale = newScale
                    val newOffset = if (newScale == 1f) {
                        Offset.Zero
                    } else {
                        offset + pan
                    }
                    offset = clampAttachmentPreviewImageOffset(
                        offset = newOffset,
                        viewportSize = viewportSize,
                        imageSize = imageSize,
                        scale = newScale,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            model = model,
            contentDescription = fileName,
            contentScale = ContentScale.Fit,
            onSuccess = { state ->
                val image = state.result.image
                imageSize = if (image.width > 0 && image.height > 0) {
                    Size(
                        width = image.width.toFloat(),
                        height = image.height.toFloat(),
                    )
                } else {
                    Size.Zero
                }
            },
            loading = {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            },
            success = {
                SubcomposeAsyncImageContent()
            },
            error = {
                AttachmentPreviewErrorPane(
                    message = stringResource(Res.string.attachment_preview_error_image_decode),
                    contentPadding = PaddingValues(),
                )
            },
        )
    }
}

internal fun clampAttachmentPreviewImageOffset(
    offset: Offset,
    viewportSize: Size,
    imageSize: Size,
    scale: Float,
): Offset {
    if (scale <= 1f) {
        return Offset.Zero
    }

    val bounds = attachmentPreviewImageOffsetBounds(
        viewportSize = viewportSize,
        imageSize = imageSize,
        scale = scale,
    )
    return Offset(
        x = offset.x.coerceInBounds(bounds.x),
        y = offset.y.coerceInBounds(bounds.y),
    )
}

internal fun attachmentPreviewImageOffsetBounds(
    viewportSize: Size,
    imageSize: Size,
    scale: Float,
): Offset {
    if (!viewportSize.isUsable() || !imageSize.isUsable()) {
        return Offset.Zero
    }

    val fittedSize = attachmentPreviewImageFittedSize(
        viewportSize = viewportSize,
        imageSize = imageSize,
    )
    return Offset(
        x = max(0f, (fittedSize.width * scale - viewportSize.width) / 2f),
        y = max(0f, (fittedSize.height * scale - viewportSize.height) / 2f),
    )
}

internal fun attachmentPreviewImageFittedSize(
    viewportSize: Size,
    imageSize: Size,
): Size {
    if (!viewportSize.isUsable() || !imageSize.isUsable()) {
        return Size.Zero
    }

    val scaleFactor = ContentScale.Fit.computeScaleFactor(
        srcSize = imageSize,
        dstSize = viewportSize,
    )
    return Size(
        width = imageSize.width * scaleFactor.scaleX,
        height = imageSize.height * scaleFactor.scaleY,
    )
}

private fun Size.isUsable(): Boolean =
    isSpecified && width > 0f && height > 0f

private fun Float.coerceInBounds(bound: Float): Float =
    if (bound <= 0f) {
        0f
    } else {
        coerceIn(-bound, bound)
    }

@Composable
private fun AttachmentPreviewText(
    content: AttachmentPreviewContent.Text,
    wrapLines: Boolean,
    contentPadding: PaddingValues,
) {
    AttachmentPreviewCodeViewer(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        code = content.code,
        wrapLines = wrapLines,
    )
}

@Composable
private fun AttachmentPreviewMarkdown(
    content: AttachmentPreviewContent.Markdown,
    markdownMode: AttachmentPreviewTextMode,
    onMarkdownModeChange: (AttachmentPreviewTextMode) -> Unit,
    wrapLines: Boolean,
    contentPadding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        val markdownModeState = rememberUpdatedState(markdownMode)
        SegmentedButtonGroup(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = Dimens.contentPadding,
                    vertical = 8.dp,
                ),
            tabState = markdownModeState,
            tabs = AttachmentPreviewTextMode.entriesList,
            onClick = onMarkdownModeChange,
            weight = 1f,
        )

        when (markdownMode) {
            AttachmentPreviewTextMode.Source -> AttachmentPreviewCodeViewer(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                code = content.code,
                wrapLines = wrapLines,
            )

            AttachmentPreviewTextMode.Rendered -> AttachmentPreviewMarkdownText(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                text = content.text,
            )
        }
    }
}

@Composable
private fun AttachmentPreviewMarkdownText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val verticalScrollState = rememberScrollState()
    SelectionContainer {
        Box(
            modifier = modifier
                .verticalScroll(verticalScrollState),
        ) {
            MarkdownText(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                markdown = text,
            )
        }
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun AttachmentPreviewCodeViewer(
    code: AttachmentPreviewCode,
    wrapLines: Boolean,
    modifier: Modifier = Modifier,
) {
    val horizontalScrollState = rememberScrollState()
    val verticalScrollState = rememberLazyListState()
    val lineIndex = code.lineIndex
    val gutterWidth = remember(lineIndex.size) {
        val digits = lineIndex.size.toString().length
        (digits * 10 + 24).dp
    }
    val codeStyle = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
    )
    val lineNumberColor = LocalContentColor.current
        .combineAlpha(MediumEmphasisAlpha)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds(),
    ) {
        val density = LocalDensity.current

        val showMinimap = maxWidth >= AttachmentPreviewMinimapVisibleMinWidth &&
                lineIndex.size > 1
        val minimapWidth = if (showMinimap) {
            val w = maxWidth / 8
            w.coerceIn(AttachmentPreviewMinimapMinWidth..AttachmentPreviewMinimapMaxWidth)
        } else 0.dp
        val minimapHeight = remember(
            density,
            lineIndex.size,
            maxHeight,
        ) {
            with(density) {
                attachmentPreviewMinimapPanelHeightPx(
                    fullHeightPx = maxHeight.toPx(),
                    lineCount = lineIndex.size,
                    maxRowPitchPx = AttachmentPreviewMinimapMaxRowPitch.toPx(),
                ).toDp()
            }
        }

        val hazeState = rememberHazeState()
        SelectionContainer {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState),
                state = verticalScrollState,
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                items(
                    count = lineIndex.size,
                    key = { it },
                ) { index ->
                    AttachmentPreviewCodeLine(
                        lineNumber = index + 1,
                        line = lineIndex.lineAt(code.annotatedString, index),
                        gutterWidth = gutterWidth,
                        minimapWidth = minimapWidth,
                        horizontalScrollState = horizontalScrollState,
                        wrapLines = wrapLines,
                        lineNumberColor = lineNumberColor,
                        codeStyle = codeStyle,
                    )
                }
            }
        }

        if (showMinimap && minimapHeight > 0.dp) {
            val containerColor = LocalSurfaceColor.current
            AttachmentPreviewCodeMinimap(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(minimapWidth)
                    .hazeEffect(state = hazeState) {
                        blurEffect {
                            blurRadius = 24.dp
                            backgroundColor = containerColor
                        }
                    }
                    .height(minimapHeight)
                    .padding(vertical = 8.dp),
                lineIndex = lineIndex,
                listState = verticalScrollState,
            )
        }
    }
}

@Composable
private fun AttachmentPreviewCodeLine(
    lineNumber: Int,
    line: AnnotatedString,
    gutterWidth: Dp,
    minimapWidth: Dp,
    horizontalScrollState: ScrollState,
    wrapLines: Boolean,
    lineNumberColor: Color,
    codeStyle: TextStyle,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        DisableSelection {
            Text(
                modifier = Modifier
                    .width(gutterWidth)
                    .padding(horizontal = 8.dp),
                text = lineNumber.toString(),
                textAlign = TextAlign.End,
                color = lineNumberColor,
                style = codeStyle,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (wrapLines) {
                        Modifier
                    } else {
                        Modifier.horizontalScroll(horizontalScrollState)
                    },
                ),
        ) {
            Text(
                modifier = Modifier
                    .padding(end = 16.dp + minimapWidth),
                text = line,
                softWrap = wrapLines,
                style = codeStyle,
            )
        }
    }
}

private enum class AttachmentPreviewTextMode(
    override val key: String,
    override val title: TextHolder,
) : TabItem {
    Source(
        key = "source",
        title = Res.string.attachment_preview_action_source.wrap(),
    ),
    Rendered(
        key = "rendered",
        title = Res.string.attachment_preview_action_rendered_preview.wrap(),
    ),
    ;

    companion object {
        val entriesList = persistentListOf(
            Source,
            Rendered,
        )
    }
}

@Composable
private fun AttachmentPreviewErrorPane(
    message: String,
    contentPadding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Placeholder(
            icon = Icons.Outlined.ErrorOutline,
            title = message,
        )
    }
}

@Composable
private fun AttachmentPreviewError.message(): String = when (this) {
    AttachmentPreviewError.UnsupportedFileType ->
        stringResource(Res.string.attachment_preview_error_unsupported_file)

    AttachmentPreviewError.UnsupportedPlatform ->
        stringResource(Res.string.attachment_preview_error_unsupported_platform)

    AttachmentPreviewError.TooLarge ->
        stringResource(Res.string.attachment_preview_error_too_large)

    AttachmentPreviewError.Network ->
        stringResource(Res.string.attachment_preview_error_network)

    AttachmentPreviewError.Decryption ->
        stringResource(Res.string.attachment_preview_error_decryption)

    AttachmentPreviewError.TextDecode ->
        stringResource(Res.string.attachment_preview_error_text_decode)

    AttachmentPreviewError.Unknown ->
        stringResource(Res.string.error_failed_unknown)
}
