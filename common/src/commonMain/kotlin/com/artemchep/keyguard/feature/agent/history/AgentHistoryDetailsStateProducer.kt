package com.artemchep.keyguard.feature.agent.history

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewRouteFactory
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.search.mapListShape
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.koin.compose.currentKoinScope

@Composable
fun produceAgentHistoryDetailsState(
    details: AgentHistoryDetails,
) = with(currentKoinScope()) {
    produceAgentHistoryDetailsState(
        details = details,
        vaultViewRouteFactory = get(),
    )
}

@Composable
fun produceAgentHistoryDetailsState(
    details: AgentHistoryDetails,
    vaultViewRouteFactory: VaultViewRouteFactory,
): Loadable<AgentHistoryDetailsState> = produceScreenState(
    key = "agent_history_details",
    initial = Loadable.Loading,
    args = arrayOf(
        details,
        vaultViewRouteFactory,
    ),
) {
    agentHistoryDetailsStateProducer(
        details = details,
        vaultViewRouteFactory = vaultViewRouteFactory,
    )
}

fun RememberStateFlowScope.agentHistoryDetailsStateProducer(
    details: AgentHistoryDetails,
    vaultViewRouteFactory: VaultViewRouteFactory,
): Flow<Loadable<AgentHistoryDetailsState>> {
    val copy = copier()

    fun onOpen(
        cipherId: String,
        accountId: String,
    ) {
        val route = vaultViewRouteFactory.create(
            itemId = cipherId,
            accountId = accountId,
        )
        val intent = NavigationIntent.Composite(
            listOf(
                NavigationIntent.PopById(screenId, exclusive = false),
                NavigationIntent.NavigateToRoute(route),
            ),
        )
        navigate(intent)
    }

    val items = details.sections
        .flatMap { section ->
            val header = AgentHistoryDetailsItem.Section(
                id = "section.${section.id}",
                title = section.title,
            )
            val values = if (section.table) {
                listOf(section.toTableItem(copy))
            } else {
                section.rows
                    .map { row ->
                        row.toItem(
                            sectionId = section.id,
                            copy = copy,
                            onOpen = ::onOpen,
                        )
                    }
            }
            listOf(header) + values
        }
        .mapListShape()
        .toImmutableList()
    val state = AgentHistoryDetailsState(
        details = details,
        items = items,
        onClose = {
            navigatePopSelf()
        },
    )
    return flowOf(Loadable.Ok(state))
}

private fun AgentHistoryDetails.Section.toTableItem(
    copy: CopyText,
): AgentHistoryDetailsItem.Table {
    val textRows = rows.filterIsInstance<AgentHistoryDetails.Row.Text>()
    return AgentHistoryDetailsItem.Table(
        id = "table.$id",
        rows = textRows.toImmutableList(),
        dropdown = textRows
            .map { row ->
                copy.FlatItemAction(
                    title = TextHolder.Value(row.label),
                    value = row.value,
                    type = row.copyType,
                )
            }
            .toImmutableList(),
    )
}

private fun AgentHistoryDetails.Row.toItem(
    sectionId: String,
    copy: CopyText,
    onOpen: (cipherId: String, accountId: String) -> Unit,
): AgentHistoryDetailsItem.Value {
    val id = "value.$sectionId.$id"
    return when (this) {
        is AgentHistoryDetails.Row.Text -> AgentHistoryDetailsItem.Value(
            id = id,
            row = this,
            dropdown = persistentListOf(
                copy.FlatItemAction(
                    title = copyType.actionRes.wrap(),
                    value = value,
                    type = copyType,
                ),
            ),
        )

        is AgentHistoryDetails.Row.Code -> AgentHistoryDetailsItem.Value(
            id = id,
            row = this,
            dropdown = persistentListOf(
                copy.FlatItemAction(
                    title = CopyText.Type.VALUE.actionRes.wrap(),
                    value = value,
                ),
            ),
        )

        is AgentHistoryDetails.Row.Reference -> {
            val accountId = accountId
            AgentHistoryDetailsItem.Value(
                id = id,
                row = this,
                dropdown = persistentListOf(),
                onClick = if (item != null && accountId != null) {
                    // lambda
                    {
                        onOpen(cipherId, accountId)
                    }
                } else {
                    null
                },
            )
        }
    }
}
