package com.artemchep.keyguard.feature.home.vault.quicksearch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.feature.keyguard.ManualAppScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnCreate
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnLoading
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnUnlock
import com.artemchep.keyguard.feature.keyguard.saveNavigationRouter
import com.artemchep.keyguard.feature.navigation.LocalNavigationRouterNode
import com.artemchep.keyguard.feature.navigation.LocalNavigationStore
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.feature.navigation.NavigationRouter
import org.kodein.di.compose.withDI

@Composable
fun QuickSearchAppScreen() {
    ManualAppScreen { vaultState ->
        val updatedNavStore by rememberUpdatedState(LocalNavigationStore.current)
        val updatedNavNode by rememberUpdatedState(LocalNavigationRouterNode.current)

        val vaultStateClassIsMain = vaultState is VaultState.Main
        remember(vaultStateClassIsMain) {
            if (vaultStateClassIsMain) {
                return@remember
            }

            saveNavigationRouter(
                store = updatedNavStore,
                node = updatedNavNode,
            )
        }

        when (vaultState) {
            is VaultState.Create -> ManualAppScreenOnCreate(vaultState)
            is VaultState.Unlock -> ManualAppScreenOnUnlock(vaultState)
            is VaultState.Loading -> ManualAppScreenOnLoading(vaultState)
            is VaultState.Main -> {
                withDI(vaultState.di) {
                    NavigationRouter(
                        id = "quick_search",
                        initial = QuickSearchRoute,
                    ) { entries ->
                        NavigationNode(
                            entries = entries,
                        )
                    }
                }
            }
        }
    }
}
