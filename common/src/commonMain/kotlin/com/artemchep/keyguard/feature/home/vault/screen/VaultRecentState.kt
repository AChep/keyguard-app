package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.runtime.Immutable
import arrow.optics.optics
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.ui.tabs.CallsTabs
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

@Immutable
@optics
data class VaultRecentState(
    val revision: Int = 0,
    val tabs: PersistentList<CallsTabs> = CallsTabs.entries.toPersistentList(),
    val onSelectTab: ((CallsTabs) -> Unit)? = null,
    val selectedTab: StateFlow<CallsTabs> = MutableStateFlow(CallsTabs.RECENTS),
    val recent: StateFlow<List<VaultItem2>> = MutableStateFlow(emptyList()),
    val sideEffects: SideEffects = SideEffects(),
) {
    companion object;

    @Immutable
    @optics
    data class SideEffects(
        val selectCipherFlow: Flow<DSecret> = emptyFlow(),
    ) {
        companion object
    }
}
