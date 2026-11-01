package com.artemchep.keyguard.feature.dragout.compose

import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import com.artemchep.keyguard.feature.dragout.model.DragOutSource
import com.artemchep.keyguard.feature.dragout.model.DragPayload

// The native Apple detail views drag through NSItemProvider and AppKit instead of Compose.
internal actual fun CompositionLocalConsumerModifierNode.prepareDragOut(payload: DragPayload): PreparedDragOut? = null

internal actual fun CompositionLocalConsumerModifierNode.promiseDragOut(source: DragOutSource): PreparedDragOut? = null
