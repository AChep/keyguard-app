package com.artemchep.keyguard.feature.home.vault.screen

sealed interface VaultListMode {
    data object Normal : VaultListMode

    data object Find : VaultListMode
}
