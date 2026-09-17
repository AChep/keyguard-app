@file:OptIn(org.koin.core.annotation.KoinDelicateAPI::class, org.koin.core.annotation.KoinExperimentalAPI::class)

package com.artemchep.keyguard.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import com.artemchep.keyguard.common.service.vault.VaultSession
import org.koin.compose.scope.UnboundKoinScope

val LocalVaultSessionId = staticCompositionLocalOf<String?> { null }

/** The session owner, not a window or screen, controls the scope's lifetime. */
@Composable
fun VaultSessionContent(session: VaultSession, content: @Composable () -> Unit) {
    val active by session.active.collectAsState()
    // Read the current value as well: a retired outgoing transition must not do
    // another lookup while Compose is processing the state-flow invalidation.
    if (!active || !session.active.value) return
    key(session.id) {
        CompositionLocalProvider(LocalVaultSessionId provides session.id) {
            UnboundKoinScope(session.scope, content = content)
        }
    }
}
