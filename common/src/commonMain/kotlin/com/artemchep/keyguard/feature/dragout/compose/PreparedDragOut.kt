package com.artemchep.keyguard.feature.dragout.compose

import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferData

/** A drag ready to start through exactly one platform transport. */
internal sealed interface PreparedDragOut {
    val close: () -> Unit

    /**
     * Compose starts the drag with [data], [handoff] runs once it has started,
     * and [ended] reads the platform's drop outcome.
     */
    class Compose(
        val data: DragAndDropTransferData,
        override val close: () -> Unit,
        val handoff: () -> Unit = {},
        val ended: (DragAndDropEvent) -> Unit = {},
    ) : PreparedDragOut

    /** A native backend starts the drag itself, hands its files off, and reports when it ends. */
    class Native(
        val start: (onEnded: () -> Unit) -> Boolean,
        override val close: () -> Unit,
    ) : PreparedDragOut
}
