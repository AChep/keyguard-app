package com.artemchep.keyguard.feature.navigation

interface NavigationIntentScope {
    val backStack: NavigationBackStack

    /**
     * If there are multiple stacks, then find and
     * return the one that has this ID, otherwise
     * create a new navigation stack.
     */
    fun getStack(id: String): NavigationBackStack
}
