package com.artemchep.keyguard.feature.dragout.compose

import android.content.ClipData
import android.content.ClipDescription
import android.view.View
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalContext
import com.artemchep.keyguard.feature.dragout.DragOutContentRegistry
import com.artemchep.keyguard.feature.dragout.model.DragOutSource
import com.artemchep.keyguard.feature.dragout.model.DragPayload
import com.artemchep.keyguard.feature.dragout.model.DragRepresentation
import com.artemchep.keyguard.feature.dragout.model.representation

internal actual fun CompositionLocalConsumerModifierNode.prepareDragOut(payload: DragPayload): PreparedDragOut? {
    val context = currentValueOf(LocalContext)
    val offers = mutableListOf<DragOutContentRegistry.Offer>()
    val entries = payload.items.mapNotNull { item ->
        val file = item.representation<DragRepresentation.File>()
        val url = item.representation<DragRepresentation.Url>()
        when {
            file != null -> {
                val offer = DragOutContentRegistry.register(context, file.content)
                offers += offer
                ClipData.Item(offer.uri) to offer.mimeType
            }

            url != null -> ClipData.Item(url.url) to ClipDescription.MIMETYPE_TEXT_PLAIN
            else -> null
        }
    }
    if (entries.isEmpty()) return null
    val description = ClipDescription(payload.label, entries.map { it.second }.distinct().toTypedArray())
    val clip = ClipData(description, entries.first().first)
    entries.drop(1).forEach { clip.addItem(it.first) }
    // Only content URIs need a read grant; links are plain text.
    val grant = if (offers.isEmpty()) 0 else View.DRAG_FLAG_GLOBAL_URI_READ
    return PreparedDragOut.Compose(
        data = DragAndDropTransferData(clipData = clip, flags = View.DRAG_FLAG_GLOBAL or grant),
        close = { offers.forEach(DragOutContentRegistry.Offer::close) },
        handoff = { offers.forEach(DragOutContentRegistry.Offer::handoff) },
        ended = { event ->
            val accepted = event.toAndroidDragEvent().result
            offers.forEach { it.ended(accepted) }
        },
    )
}

// A recipient reads on drop, and Android must grant its URI before the drag starts.
internal actual fun CompositionLocalConsumerModifierNode.promiseDragOut(source: DragOutSource): PreparedDragOut? = null
