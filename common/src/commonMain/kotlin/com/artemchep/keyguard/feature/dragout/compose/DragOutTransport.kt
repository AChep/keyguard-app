package com.artemchep.keyguard.feature.dragout.compose

import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import com.artemchep.keyguard.feature.dragout.model.DragOutSource
import com.artemchep.keyguard.feature.dragout.model.DragPayload

/**
 * Maps a payload onto the platform's drag transport, or null when no item has a
 * representation it supports. A returned transfer owns [payload]; otherwise the
 * caller closes it, which is idempotent. Call only while the node is attached.
 */
internal expect fun CompositionLocalConsumerModifierNode.prepareDragOut(payload: DragPayload): PreparedDragOut?

/**
 * Starts a drag of [source]'s promised file before it is prepared, or null when the
 * platform can't. The transfer prepares the file itself once the drag has started.
 * Call only while the node is attached.
 */
internal expect fun CompositionLocalConsumerModifierNode.promiseDragOut(source: DragOutSource): PreparedDragOut?
