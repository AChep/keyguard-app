package com.artemchep.keyguard.feature.equivalentdomains

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.DEquivalentDomains
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.usecase.GetEquivalentDomains
import com.artemchep.keyguard.feature.generator.emailrelay.EmailRelayListState
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.search.mapListShape
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.DirectDI
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun produceEquivalentDomainsScreenState(
    args: EquivalentDomainsRoute.Args,
) = with(localDI().direct) {
    produceEquivalentDomainsScreenState(
        args = args,
        directDI = this,
        getEquivalentDomains = instance(),
    )
}

@Composable
fun produceEquivalentDomainsScreenState(
    args: EquivalentDomainsRoute.Args,
    directDI: DirectDI,
    getEquivalentDomains: GetEquivalentDomains,
): EquivalentDomainsState = produceScreenState(
    key = "equivalent_domains",
    initial = EquivalentDomainsState(),
    args = arrayOf(
        args,
        getEquivalentDomains,
    ),
) {
    equivalentDomainsScreenStateProducer(
        args = args,
        getEquivalentDomains = getEquivalentDomains,
    )
}

suspend fun RememberStateFlowScope.equivalentDomainsScreenStateProducer(
    args: EquivalentDomainsRoute.Args,
    getEquivalentDomains: GetEquivalentDomains,
): Flow<EquivalentDomainsState> {
    val equivalentDomainsComparator = Comparator { a: DEquivalentDomains, b: DEquivalentDomains ->
        a.id.compareTo(b.id, ignoreCase = true)
    }
    val equivalentDomainsFlow = getEquivalentDomains()
        .map { equivalentDomains ->
            equivalentDomains
                .filter { it.accountId == args.accountId.id }
                .sortedWith(equivalentDomainsComparator)
        }
        .shareIn(screenScope, SharingStarted.Lazily, replay = 1)

    val contentFlow = equivalentDomainsFlow
        .map { equivalentDomains ->
            val items = equivalentDomains
                .map {
                    val title = it.domains.joinToString()
                    EquivalentDomainsState.Content.Item.Content(
                        key = it.id,
                        title = title,
                        excluded = it.excluded,
                        global = it.global,
                        onClick = null,
                    )
                }
                .toList()
            val itemsReShaped = items
                .mapListShape()
                .toImmutableList()
            EquivalentDomainsState.Content(
                items = itemsReShaped,
            )
        }
    return contentFlow
        .map { content ->
            EquivalentDomainsState(
                content = Loadable.Ok(content),
                onAdd = null,
            )
        }
}
