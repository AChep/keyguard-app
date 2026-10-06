package com.artemchep.keyguard.apple.filter

import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.model.DCipherFilter
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.filter.list.cipherFiltersListStateProducer
import com.artemchep.keyguard.feature.filter.view.CipherFilterViewDialogRoute
import com.artemchep.keyguard.feature.filter.view.cipherFilterViewStateProducer
import com.artemchep.keyguard.feature.home.vault.model.FilterItem
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.model.VaultActionSnapshot
import com.artemchep.keyguard.apple.model.buildMenuActionSnapshots
import com.artemchep.keyguard.platform.LeContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.koin.core.scope.Scope

data class CipherFiltersListSnapshot(
    val loaded: Boolean,
    val query: String,
    val queryRevision: Int,
    val items: List<CipherFilterRowSnapshot>,
) {
    companion object {
        val empty = CipherFiltersListSnapshot(false, "", 0, emptyList())
    }
}

data class CipherFilterRowSnapshot(
    val id: String,
    val name: String,
)

data class CipherFilterDetailSnapshot(
    val loaded: Boolean,
    val title: String,
    val items: List<CipherFilterDetailItemSnapshot>,
    /** Toolbar actions, routed by id via [com.artemchep.keyguard.apple.KeyguardCore.invokeEntryAction]. */
    val actions: List<VaultActionSnapshot>,
) {
    companion object {
        val empty = CipherFilterDetailSnapshot(false, "", emptyList(), emptyList())
    }
}

data class CipherFilterDetailItemSnapshot(
    val isSection: Boolean,
    val id: String,
    val title: String,
    val text: String?,
)

/**
 * The two-pane Compose `CipherFiltersRoute` flattened to two native stacked screens: a list of saved filters
 * and a read-only detail of one. The list caches each row's [DCipherFilter] so a row tap can push the detail
 * by id (the producer's own row click emits an undecomposed Composite, so it is bypassed).
 */
internal class CipherFiltersController(
    private val ctx: CoreContext,
) {
    // Main-confined (written inside collectOnMain, read on the caller's main thread).
    private var queryHandler: ((String) -> Unit)? = null
    private var latestModels: Map<String, DCipherFilter> = emptyMap()

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun produceFiltersInto(
        scope: CoroutineScope,
        sessionKoin: Scope,
        interceptor: (NavigationIntent) -> Boolean,
        publish: suspend (CipherFiltersListSnapshot) -> Unit,
    ) {
        val producerFlow = with(sessionKoin) {
            ctx.koin.newHeadlessStateFlowScope("cipher_filters", scope, interceptor)
                .cipherFiltersListStateProducer(
                    getCipherFilters = get(),
                    removeCipherFilterById = get(),
                    renameCipherFilter = get(),
                    confirmationRouteFactory = get(),
                )
        }
        producerFlow
            .flatMapLatest { loadable ->
                val state = loadable.getOrNull()
                    ?: return@flatMapLatest flowOf(
                        ListProjection(CipherFiltersListSnapshot.empty, null, emptyMap()),
                    )
                // state.filter is a StateFlow<Filter>; map over it so the search field
                // (text + revision) stays live.
                state.filter.map { filter ->
                    val content = state.content.getOrNull()?.getOrNull()
                    val items = content?.items.orEmpty()
                    val rows = items.map { CipherFilterRowSnapshot(id = it.key, name = it.name.text) }
                    val models = items.associate { it.key to it.data }
                    ListProjection(
                        snapshot = CipherFiltersListSnapshot(
                            loaded = content != null,
                            query = filter.query.text,
                            queryRevision = filter.query.textRevision,
                            items = rows,
                        ),
                        onQueryChange = filter.query.onChange,
                        models = models,
                    )
                }
            }
            .collectOnMain { projection ->
                queryHandler = projection.onQueryChange
                latestModels = projection.models
                publish(projection.snapshot)
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun produceFilterDetailInto(
        scope: CoroutineScope,
        sessionKoin: Scope,
        filterId: String,
        fallbackModel: DCipherFilter?,
        interceptor: (NavigationIntent) -> Boolean,
        publish: suspend (CipherFilterDetailSnapshot, Map<String, () -> Unit>) -> Unit,
    ) {
        // Prefer the cached row model (the list populated it); fall back to a model that
        // arrived inline on the route (a direct CipherFilterViewFullRoute navigation).
        val model = latestModels[filterId] ?: fallbackModel
        if (model == null) {
            publish(CipherFilterDetailSnapshot.empty, emptyMap())
            return
        }
        val leContext = ctx.koin.get<LeContext>()
        val producerFlow = with(sessionKoin) {
            ctx.koin.newHeadlessStateFlowScope("cipher_filter_detail", scope, interceptor)
                .cipherFilterViewStateProducer(
                    args = CipherFilterViewDialogRoute.Args(model = model),
                    getCipherFilters = get(),
                    removeCipherFilterById = get(),
                    renameCipherFilter = get(),
                    getAccounts = get(),
                    getProfiles = get(),
                    getOrganizations = get(),
                    getCollections = get(),
                    getTags = get(),
                    getFolders = get(),
                    getCiphers = get(),
                    confirmationRouteFactory = get(),
                )
        }
        producerFlow
            .flatMapLatest { loadable ->
                val state = loadable.getOrNull()
                    ?: return@flatMapLatest flowOf(
                        DetailProjection(CipherFilterDetailSnapshot.empty, emptyMap()),
                    )
                combine(state.toolbarFlow, state.filterFlow) { toolbar, filter ->
                    val handlers = LinkedHashMap<String, () -> Unit>()
                    val actions = buildMenuActionSnapshots(
                        actions = toolbar.actions,
                        idPrefix = "filter",
                        leContext = leContext,
                        handlers = handlers,
                    )
                    val items = filter.items.map { fi ->
                        when (fi) {
                            is FilterItem.Section -> CipherFilterDetailItemSnapshot(
                                isSection = true,
                                id = fi.id,
                                title = fi.text,
                                text = null,
                            )

                            is FilterItem.ChipItem -> CipherFilterDetailItemSnapshot(
                                isSection = false,
                                id = fi.id,
                                title = fi.title,
                                text = fi.text,
                            )

                            is FilterItem.ListItem -> CipherFilterDetailItemSnapshot(
                                isSection = false,
                                id = fi.id,
                                title = fi.title,
                                text = fi.text,
                            )
                        }
                    }
                    DetailProjection(
                        snapshot = CipherFilterDetailSnapshot(
                            loaded = true,
                            title = toolbar.model?.name ?: model.name,
                            items = items,
                            actions = actions,
                        ),
                        handlers = handlers,
                    )
                }
            }
            .collectOnMain { projection ->
                publish(projection.snapshot, projection.handlers)
            }
    }

    fun setQuery(text: String) {
        queryHandler?.invoke(text)
    }

    /** The cached display name of a filter row (used as the detail's pushed title). */
    fun titleFor(id: String): String? = latestModels[id]?.name

    private data class ListProjection(
        val snapshot: CipherFiltersListSnapshot,
        val onQueryChange: ((String) -> Unit)?,
        val models: Map<String, DCipherFilter>,
    )

    private data class DetailProjection(
        val snapshot: CipherFilterDetailSnapshot,
        val handlers: Map<String, () -> Unit>,
    )
}
