package com.artemchep.keyguard.feature.cliagent.history

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryRequestType
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryResponseType
import com.artemchep.keyguard.common.model.GroupableShapeItem
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.feature.agent.history.AgentHistoryDetails
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Immutable
sealed interface CliAgentHistoryItem {
    val id: String

    @Immutable
    data class Section(
        override val id: String = Uuid.random().toString(),
        val text: String? = null,
        val caps: Boolean = true,
    ) : CliAgentHistoryItem

    @Immutable
    data class Value(
        override val id: String,
        val caller: String,
        val description: String,
        /** The command line and its directory, or the path of a local `.env` file. */
        val commandText: String?,
        val formattedDate: String,
        val responseText: String,
        val request: CliAgentUsageHistoryRequestType,
        val response: CliAgentUsageHistoryResponseType,
        val createdAt: Instant,
        val details: AgentHistoryDetails,
        val onClick: (() -> Unit)? = null,
        val shapeState: Int = ShapeState.ALL,
    ) : CliAgentHistoryItem, GroupableShapeItem<Value> {
        override fun withShape(shape: Int) = copy(shapeState = shape)
    }
}
