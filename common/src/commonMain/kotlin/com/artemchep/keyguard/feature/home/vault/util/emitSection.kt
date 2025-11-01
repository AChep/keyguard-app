package com.artemchep.keyguard.feature.home.vault.util

import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.withIndex

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun FlowCollector<VaultViewItem>.emitWithSection(
    provideSection: suspend () -> VaultViewItem.Section,
    block: suspend FlowCollector<VaultViewItem>.() -> Unit,
) {
    // Create an inner collector flow and wait for the
    // first item to be emitted, in which cause also emit
    // the section.
    flow { block() }
        .withIndex()
        .flatMapConcat { (index, item) ->
            // Emit section as the first
            // item.
            if (index == 0) {
                return@flatMapConcat flow {
                    val section = provideSection()
                    emit(section)
                    emit(item)
                }
            }

            flowOf(item)
        }
        .collect(this)
}
