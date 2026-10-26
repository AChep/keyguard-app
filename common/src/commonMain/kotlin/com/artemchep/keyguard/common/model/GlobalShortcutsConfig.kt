package com.artemchep.keyguard.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GlobalShortcutsConfig(
    @SerialName(QUICK_SEARCH) val quickSearch: GlobalShortcutConfig = GlobalShortcutConfig(),
    @SerialName(LOCK_VAULT) val lockVault: GlobalShortcutConfig = GlobalShortcutConfig(),
) {
    companion object {
        private const val QUICK_SEARCH = "quick_search"
        private const val LOCK_VAULT = "lock_vault"

        /** The JSON key of the [action]'s entry. */
        fun serialName(action: GlobalShortcutAction): String =
            when (action) {
                GlobalShortcutAction.QuickSearch -> QUICK_SEARCH
                GlobalShortcutAction.LockVault -> LOCK_VAULT
            }
    }

    operator fun get(action: GlobalShortcutAction): GlobalShortcutConfig =
        when (action) {
            GlobalShortcutAction.QuickSearch -> quickSearch
            GlobalShortcutAction.LockVault -> lockVault
        }

    fun with(
        action: GlobalShortcutAction,
        config: GlobalShortcutConfig,
    ): GlobalShortcutsConfig =
        when (action) {
            GlobalShortcutAction.QuickSearch -> copy(quickSearch = config)
            GlobalShortcutAction.LockVault -> copy(lockVault = config)
        }
}
