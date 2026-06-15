package com.artemchep.keyguard.feature.sshagent.history

import androidx.compose.runtime.Immutable
import arrow.optics.optics
import com.artemchep.keyguard.common.model.GroupableShapeItem
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.model.SshUsageHistoryRequestType
import com.artemchep.keyguard.common.model.SshUsageHistoryResponseType
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Immutable
@optics
sealed interface SshAgentHistoryItem {
    companion object

    val id: String

    @Immutable
    data class Section(
        override val id: String = Uuid.random().toString(),
        val text: String? = null,
        val caps: Boolean = true,
    ) : SshAgentHistoryItem {
        companion object
    }

    @Immutable
    data class Value(
        override val id: String,
        val caller: String,
        val description: String,
        val formattedDate: String,
        val responseText: String,
        val request: SshUsageHistoryRequestType,
        val response: SshUsageHistoryResponseType,
        val createdAt: Instant,
        val shapeState: Int = ShapeState.ALL,
    ) : SshAgentHistoryItem, GroupableShapeItem<Value> {
        companion object;

        override fun withShape(shape: Int) = copy(shapeState = shape)
    }
}
