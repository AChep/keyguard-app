package com.artemchep.keyguard.feature.navigation

import kotlinx.collections.immutable.PersistentList

data class NavigationBackStack(
    val id: String,
    val entries: PersistentList<NavigationEntry>,
)
