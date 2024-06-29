package com.artemchep.keyguard.feature.navigation

import kotlinx.collections.immutable.PersistentList

interface NavigationIntentScope {
    val backStack: PersistentList<NavigationEntry>
}
