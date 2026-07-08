package com.artemchep.keyguard.feature.gpgagent.history

import androidx.compose.runtime.Immutable
import arrow.optics.optics
import com.artemchep.keyguard.common.model.GpgUsageHistoryRequestType
import com.artemchep.keyguard.common.model.GpgUsageHistoryResponseType
import com.artemchep.keyguard.common.model.GroupableShapeItem
import com.artemchep.keyguard.common.model.ShapeState
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Immutable
@optics
sealed interface GpgAgentHistoryItem {
    companion object

    val id: String

    @Immutable
    data class Section(
        override val id: String = Uuid.random().toString(),
        val text: String? = null,
        val caps: Boolean = true,
    ) : GpgAgentHistoryItem {
        companion object
    }

    @Immutable
    data class Value(
        override val id: String,
        val caller: String,
        val description: String,
        val formattedDate: String,
        val responseText: String,
        val request: GpgUsageHistoryRequestType,
        val response: GpgUsageHistoryResponseType,
        val createdAt: Instant,
        val shapeState: Int = ShapeState.ALL,
    ) : GpgAgentHistoryItem, GroupableShapeItem<Value> {
        companion object;

        override fun withShape(shape: Int) = copy(shapeState = shape)
    }
}
