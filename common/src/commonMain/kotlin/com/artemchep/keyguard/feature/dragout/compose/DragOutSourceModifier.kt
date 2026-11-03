package com.artemchep.keyguard.feature.dragout.compose

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropSourceModifierNode
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTargetModifierNode
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import com.artemchep.keyguard.feature.dragout.model.DragOutSource
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** A row's leading icon: a long-press handle on touch devices, a mouse drag elsewhere. */
@Composable
internal fun Modifier.dragOutHandle(
    source: DragOutSource?,
    enabled: Boolean = true,
    exclusiveTouch: Boolean = true,
): Modifier = dragOutSource(
    source = source,
    enabled = enabled,
    touch = CurrentPlatform is Platform.Mobile,
    exclusiveTouch = exclusiveTouch,
)

/** A row's text: only a desktop mouse drags it, so touch keeps scrolling and selecting the row. */
@Composable
internal fun Modifier.dragOutArea(
    source: DragOutSource?,
    enabled: Boolean = true,
): Modifier = dragOutSource(
    source = source,
    enabled = enabled && CurrentPlatform is Platform.Desktop,
    touch = false,
)

@Composable
internal fun Modifier.dragOutSource(
    source: DragOutSource?,
    enabled: Boolean,
    touch: Boolean,
    /**
     * A touch handle claims the whole gesture, so a parent's long press never sees it.
     * Otherwise taps pass through to the parent, which must not handle long presses.
     */
    exclusiveTouch: Boolean = true,
): Modifier {
    if (source == null || !enabled) return this
    return this then DragOutElement(
        source, touch, exclusiveTouch,
        MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        MaterialTheme.colorScheme.surfaceContainer,
    )
}

private data class DragOutElement(
    val drag: DragOutSource,
    val touch: Boolean,
    val exclusiveTouch: Boolean,
    val labelStyle: TextStyle,
    val background: Color,
) : ModifierNodeElement<DragOutNode>() {
    override fun create() = DragOutNode(drag, touch, exclusiveTouch, labelStyle, background)
    override fun update(node: DragOutNode) {
        node.update(drag, touch, exclusiveTouch, labelStyle, background)
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "dragOutSource"
    }
}

private class DragOutNode(
    private var drag: DragOutSource,
    private var touch: Boolean,
    private var exclusiveTouch: Boolean,
    private var labelStyle: TextStyle,
    private var background: Color,
) : DelegatingNode(), KeyInputModifierNode, CompositionLocalConsumerModifierNode {
    private var preparation: Job? = null
    private var platformPreparation: Job? = null
    private var pressed = false
    private var transferred = false
    private var prepared: PreparedDragOut? = null
    private var stopObservingCancellation: (() -> Unit)? = null
    // Native startup and capability handoff may fail after asynchronous preparation.
    @Suppress("TooGenericExceptionCaught")
    private val source = delegate(DragAndDropSourceModifierNode { _ ->
        val transfer = prepared as? PreparedDragOut.Compose
        if (pressed && transfer != null) {
            try {
                transferred = true
                val text = measureLabel()
                val started = startDragAndDropTransfer(
                    transferData = transfer.data,
                    decorationSize = Size(text.size.width + DECORATION_PADDING, text.size.height + DECORATION_PADDING),
                    drawDragDecoration = {
                        drawRoundRect(background, cornerRadius = CornerRadius(8.dp.toPx()))
                        drawText(text, topLeft = Offset(DECORATION_PADDING / 2, DECORATION_PADDING / 2))
                    },
                )
                if (started) transfer.handoff() else abort()
            } catch (failure: Throwable) {
                fail(failure)
            }
        }
    })
    @Suppress("unused")
    private val target = delegate(DragAndDropTargetModifierNode(
        shouldStartDragAndDrop = { prepared != null },
        target = object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent) = false
            override fun onEnded(event: DragAndDropEvent) {
                if (transferred) (prepared as? PreparedDragOut.Compose)?.ended?.invoke(event)
                finish()
            }
        },
    ))
    // Platform transport failures must report through the screen rather than escape Compose.
    @Suppress("TooGenericExceptionCaught")
    private val pointer = delegate(SuspendingPointerInputModifierNode {
        val onStart: (Offset) -> Unit = { _ ->
            cancel()
            pressed = true
            stopObservingCancellation = drag.observeCancellation(::cancel)
            if (!startPromisedTransfer()) preparation = drag.start { payload ->
                if (payload != null) {
                    if (!pressed || !isAttached) payload.close()
                    else {
                        // A previous drag may still hold `prepared`, so track this payload's owner here.
                        var owned = false
                        platformPreparation = coroutineScope.launch {
                            try {
                                val transfer = prepareDragOut(payload)
                                if (pressed && isAttached && transfer != null) {
                                    owned = true
                                    prepared = transfer
                                    startPreparedTransfer(transfer)
                                } else {
                                    transfer?.close?.invoke()
                                }
                            } catch (e: Throwable) {
                                fail(e)
                            }
                        }.apply {
                            // Cancellation before startup skips the body, but never this handler.
                            invokeOnCompletion { if (!owned) payload.close() }
                        }
                    }
                }
            }
        }
        if (touch) {
            awaitEachGesture {
                // A dedicated touch handle prevents the parent row's
                // selection long press from claiming the same gesture.
                val down = awaitFirstDown()
                if (exclusiveTouch) down.consume()
                val longPress = awaitLongPressOrCancellation(down.id)
                if (longPress != null) {
                    onStart(longPress.position)
                    drag(longPress.id) { it.consume() }
                    cancel()
                }
            }
        } else {
            detectDragGestures(
                onDragStart = onStart,
                onDragEnd = ::cancel,
                onDragCancel = ::cancel,
            ) { change, _ -> change.consume() }
        }
    })

    fun update(
        drag: DragOutSource,
        touch: Boolean,
        exclusiveTouch: Boolean,
        labelStyle: TextStyle,
        background: Color,
    ) {
        this.labelStyle = labelStyle
        this.background = background
        val gestureChanged = this.touch != touch || this.exclusiveTouch != exclusiveTouch
        if (this.drag !== drag || gestureChanged) {
            cancel()
            this.drag = drag
            this.touch = touch
            this.exclusiveTouch = exclusiveTouch
            pointer.resetPointerInputHandler()
        }
    }

    /** Measured only when a drag starts; most rows never start one. */
    private fun measureLabel(): TextLayoutResult {
        val density = currentValueOf(LocalDensity)
        return TextMeasurer(
            defaultFontFamilyResolver = currentValueOf(LocalFontFamilyResolver),
            defaultDensity = density,
            defaultLayoutDirection = currentValueOf(LocalLayoutDirection),
        ).measure(
            text = drag.label,
            style = labelStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = with(density) { LABEL_MAX_WIDTH.roundToPx() }),
        )
    }

    /** A promised file's drag starts at once, and its transfer prepares the file. */
    // Native startup may fail; report it like a failed preparation.
    @Suppress("TooGenericExceptionCaught")
    private fun startPromisedTransfer(): Boolean {
        val transfer = promiseDragOut(drag) ?: return false
        try {
            prepared = transfer
            startPreparedTransfer(transfer)
        } catch (e: Throwable) {
            fail(e)
        }
        return true
    }

    private fun startPreparedTransfer(transfer: PreparedDragOut) {
        if (transfer !is PreparedDragOut.Native) {
            // Keep the anchor inside the source while the pointer may already be outside it.
            source.requestDragAndDropTransfer(Offset.Zero)
            return
        }
        val ownerScope = coroutineScope
        transferred = true
        val started = transfer.start {
            ownerScope.launch {
                // A native backend may finish inline; start must return before retirement.
                yield()
                if (prepared === transfer) finish()
            }
        }
        if (!started) abort()
    }

    /** Ends a drag the platform never took. */
    private fun abort() {
        transferred = false
        cancel()
    }

    /** Ends a drag that failed to start, and reports the failure unless it is a cancellation. */
    private fun fail(failure: Throwable) {
        abort()
        failure.throwIfFatalOrCancellation()
        drag.reportError(failure)
    }

    private fun cancel() {
        pressed = false
        preparation?.cancel()
        platformPreparation?.cancel()
        if (!transferred) finish()
    }

    private fun finish() {
        stopObservingCancellation?.invoke()
        stopObservingCancellation = null
        prepared?.close?.invoke()
        prepared = null
        transferred = false
    }

    override fun onDetach() {
        cancel()
        finish()
    }

    override fun onPreKeyEvent(event: KeyEvent): Boolean {
        if (event.key != Key.Escape) return false
        cancel()
        return true
    }
    override fun onKeyEvent(event: KeyEvent) = false
}

private const val DECORATION_PADDING = 24f
private val LABEL_MAX_WIDTH = 280.dp
