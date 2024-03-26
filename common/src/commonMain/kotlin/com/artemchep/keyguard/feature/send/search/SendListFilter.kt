package com.artemchep.keyguard.feature.send.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import arrow.core.partially1
import arrow.core.widen
import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.common.model.DSendFilter
import com.artemchep.keyguard.common.model.iconImageVector
import com.artemchep.keyguard.common.model.titleH
import com.artemchep.keyguard.feature.home.vault.component.rememberSecretAccentColor
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.send.search.filter.FilterSendHolder
import com.artemchep.keyguard.feature.send.search.filter.SendFilterItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.icons.AccentColors
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.icons.generateAccentColorsByAccountId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import org.kodein.di.DirectDI

private fun <T, R> mapCiphers(
    flow: Flow<List<T>>,
    getter: (T) -> R,
) where R : Any? = flow
    .map { ciphers ->
        ciphers
            .asSequence()
            .map(getter)
            .toSet()
    }
    .distinctUntilChanged()

data class CreateFilterResult(
    val filterFlow: Flow<FilterSendHolder>,
    val onToggle: (String, Set<DSendFilter.Primitive>) -> Unit,
    val onClear: () -> Unit,
)

suspend fun RememberStateFlowScope.createFilter(): CreateFilterResult {
    val emptyState = FilterSendHolder(
        state = mapOf(),
    )

    val filterSink = mutablePersistedFlow<FilterSendHolder, String>(
        key = "ciphers.filters",
        serialize = { json, value ->
            json.encodeToString(value)
        },
        deserialize = { json, value ->
            json.decodeFromString(value)
        },
    ) { emptyState }
    val onClear = {
        filterSink.value = emptyState
    }
    val onToggle = { sectionId: String, filters: Set<DSendFilter.Primitive> ->
        filterSink.update { holder ->
            val activeFilters = holder.state.getOrElse(sectionId) { emptySet() }
            val pendingFilters = filters
                .filter { it !in activeFilters }

            val newFilters = if (pendingFilters.isNotEmpty()) {
                // Add the filters from a clicked item if
                // not all of them are already active.
                activeFilters + pendingFilters
            } else {
                activeFilters - filters
            }
            val newState = holder.state + (sectionId to newFilters)
            holder.copy(
                state = newState,
            )
        }
    }
    return CreateFilterResult(
        filterFlow = filterSink,
        onToggle = onToggle,
        onClear = onClear,
    )
}

data class OurFilterResult(
    val rev: Int = 0,
    val items: List<SendFilterItem> = emptyList(),
    val onClear: (() -> Unit)? = null,
)

data class FilterParams(
    val section: Section = Section(),
) {
    data class Section(
        val account: Boolean = true,
        val type: Boolean = true,
        val misc: Boolean = true,
    )
}

suspend fun <
        Output : Any,
        Account,
        Secret,
        > RememberStateFlowScope.ah(
    directDI: DirectDI,
    outputGetter: (Output) -> DSend,
    outputFlow: Flow<List<Output>>,
    accountGetter: (Account) -> DAccount,
    accountFlow: Flow<List<Account>>,
    cipherGetter: (Secret) -> DSend,
    cipherFlow: Flow<List<Secret>>,
    input: CreateFilterResult,
    params: FilterParams = FilterParams(),
): Flow<OurFilterResult> {
    val storage = kotlin.run {
        val disk = loadDiskHandle("ciphers.filter")
        PersistedStorage.InDisk(disk)
    }

    val collapsedSectionIdsSink =
        mutablePersistedFlow<List<String>>("ciphers.sections", storage) { emptyList() }

    fun toggleSection(sectionId: String) {
        collapsedSectionIdsSink.update {
            val shouldAdd = sectionId !in it
            if (shouldAdd) {
                it + sectionId
            } else {
                it - sectionId
            }
        }
    }

    val outputCipherFlow = outputFlow
        .map { list ->
            list
                .map { outputGetter(it) }
        }

    val filterTypesWithCiphers = mapCiphers(cipherFlow) { cipherGetter(it).type }
    val filterAccountsWithCiphers = mapCiphers(cipherFlow) { cipherGetter(it).accountId }

    fun Flow<List<SendFilterItem>>.filterSection(
        enabled: Boolean,
    ) = if (enabled) {
        this
    } else {
        flowOf(emptyList())
    }

    fun Flow<List<SendFilterItem.Item>>.aaa(
        sectionId: String,
        sectionTitle: String,
        collapse: Boolean = true,
    ) = this
        .combine(input.filterFlow) { items, filterHolder ->
            items
                .map { item ->
                    val shouldBeChecked = kotlin.run {
                        val activeFilters = filterHolder.state[item.filterSectionId].orEmpty()
                        item.filters
                            .all { itemFilter ->
                                itemFilter in activeFilters
                            }
                    }
                    if (shouldBeChecked == item.checked) {
                        return@map item
                    }

                    item.copy(checked = shouldBeChecked)
                }
        }
        .distinctUntilChanged()
        .map { items ->
            if (items.size <= 1 && collapse || items.isEmpty()) {
                // Do not show a single filter item.
                return@map emptyList<SendFilterItem>()
            }

            items
                .widen<SendFilterItem, SendFilterItem.Item>()
                .toMutableList()
                .apply {
                    val sectionItem = SendFilterItem.Section(
                        sectionId = sectionId,
                        text = sectionTitle,
                        onClick = {
                            toggleSection(sectionId)
                        },
                    )
                    add(0, sectionItem)
                }
        }

    val setOfNull = setOf(null)

    val typeSectionId = "type"
    val accountSectionId = "account"
    val miscSectionId = "misc"

    fun createFilterAction(
        sectionId: String,
        filter: Set<DSendFilter.Primitive>,
        filterSectionId: String = sectionId,
        title: String,
        text: String? = null,
        tint: AccentColors? = null,
        icon: ImageVector? = null,
    ) = SendFilterItem.Item(
        sectionId = sectionId,
        filterSectionId = filterSectionId,
        filters = filter,
        leading = when {
            icon != null -> {
                // composable
                {
                    IconBox(main = icon)
                }
            }

            tint != null -> {
                // composable
                {
                    Box(
                        modifier = Modifier
                            .padding(2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val color = rememberSecretAccentColor(
                            accentLight = tint.light,
                            accentDark = tint.dark,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(color, CircleShape),
                        )
                    }
                }
            }

            else -> null
        },
        title = title,
        text = text,
        onClick = input.onToggle
            .partially1(filterSectionId)
            .partially1(filter),
        checked = false,
        fill = false,
        indent = 0,
    )

    fun createAccountFilterAction(
        accountIds: Set<String?>,
        title: String,
        text: String,
        tint: AccentColors? = null,
        icon: ImageVector? = null,
    ) = createFilterAction(
        sectionId = accountSectionId,
        filter = accountIds
            .asSequence()
            .map { accountId ->
                DSendFilter.ById(
                    id = accountId,
                    what = DSendFilter.ById.What.ACCOUNT,
                )
            }
            .toSet(),
        title = title,
        text = text,
        tint = tint,
        icon = icon,
    )

    fun createTypeFilterAction(
        type: DSend.Type,
    ) = createFilterAction(
        sectionId = typeSectionId,
        filter = setOf(
            DSendFilter.ByType(type),
        ),
        title = translate(type.titleH()),
        icon = type.iconImageVector(),
    )

    val filterAccountListFlow = accountFlow
        .map { accounts ->
            accounts
                .map { account ->
                    val model = accountGetter(account)
                    val accountId = model.accountId()
                    val tint = generateAccentColorsByAccountId(accountId)
                    createAccountFilterAction(
                        accountIds = setOf(
                            model.accountId(),
                        ),
                        title = model.username,
                        text = model.host,
                        tint = tint,
                    )
                }
        }
        .combine(filterAccountsWithCiphers) { items, accountIds ->
            items
                .filter { filterItem ->
                    filterItem.filters
                        .any { filter ->
                            val filterFixed = filter as DSendFilter.ById
                            require(filterFixed.what == DSendFilter.ById.What.ACCOUNT)
                            filterFixed.id in accountIds
                        }
                }
        }
        .aaa(
            sectionId = accountSectionId,
            sectionTitle = translate(Res.strings.account),
        )
        .filterSection(params.section.account)

    val filterTypesAll = listOf(
        DSend.Type.Text to createTypeFilterAction(
            type = DSend.Type.Text,
        ),
        DSend.Type.File to createTypeFilterAction(
            type = DSend.Type.File,
        ),
    )

    val filterTypeListFlow = filterTypesWithCiphers
        .map { types ->
            filterTypesAll.mapNotNull { (type, item) ->
                item.takeIf { type in types }
            }
        }
        .aaa(
            sectionId = typeSectionId,
            sectionTitle = translate(Res.strings.type),
        )
        .filterSection(params.section.type)

    val filterMiscAll = listOf<SendFilterItem.Item>(
    )

    val filterMiscListFlow = flowOf(Unit)
        .map {
            filterMiscAll
        }
        .aaa(
            sectionId = miscSectionId,
            sectionTitle = translate(Res.strings.misc),
            collapse = false,
        )
        .filterSection(params.section.misc)

    return combine(
        filterAccountListFlow,
        filterTypeListFlow,
        filterMiscListFlow,
    ) { a -> a.flatMap { it } }
        .combine(collapsedSectionIdsSink) { items, collapsedSectionIds ->
            var skippedSectionId: String? = null

            val out = mutableListOf<SendFilterItem>()
            items.forEach { item ->
                if (item is SendFilterItem.Section) {
                    val collapsed = item.sectionId in collapsedSectionIds
                    skippedSectionId = item.sectionId.takeIf { collapsed }

                    out += if (collapsed) {
                        item.copy(expanded = false)
                    } else {
                        item
                    }
                } else {
                    if (item.sectionId != skippedSectionId) {
                        out += item
                    }
                }
            }
            out
        }
        .combine(outputCipherFlow) { items, outputCiphers ->
            val checkedSectionIds = items
                .asSequence()
                .mapNotNull {
                    when (it) {
                        is SendFilterItem.Section -> null
                        is SendFilterItem.Item -> it.takeIf { it.checked }
                            ?.sectionId
                    }
                }
                .toSet()

            val out = mutableListOf<SendFilterItem>()
            items.forEach { item ->
                when (item) {
                    is SendFilterItem.Section -> out += item
                    is SendFilterItem.Item -> {
                        val fastEnabled = item.checked || item.sectionId in checkedSectionIds
                        val enabled = fastEnabled || kotlin.run {
                            item.filters
                                .any { filter ->
                                    val filterPredicate = filter.prepare(directDI, outputCiphers)
                                    outputCiphers.any(filterPredicate)
                                }
                        }

                        if (enabled) {
                            out += item
                            return@forEach
                        }

                        out += item.copy(onClick = null)
                    }
                }
            }
            out
        }
        .combine(input.filterFlow) { a, b ->
            OurFilterResult(
                rev = b.id,
                items = a,
                onClear = input.onClear.takeIf { b.id != 0 },
            )
        }
        .flowOn(Dispatchers.Default)
}
