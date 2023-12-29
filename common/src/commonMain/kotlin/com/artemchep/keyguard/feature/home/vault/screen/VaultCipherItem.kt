package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.runtime.Immutable
import arrow.optics.optics

@Immutable
@optics
sealed interface VaultCipherItem {
    companion object;

    val id: String
    val title: String

    data class Password(
        override val id: String,
        override val title: String,
        val username: String,
        val password: String,
    ) : VaultCipherItem
}
