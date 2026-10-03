package com.artemchep.keyguard.feature.home.vault.screen

import com.artemchep.keyguard.feature.home.vault.model.SortItem

data class VaultSortState(
    val items: List<SortItem>,
    val config: ComparatorHolder,
)
