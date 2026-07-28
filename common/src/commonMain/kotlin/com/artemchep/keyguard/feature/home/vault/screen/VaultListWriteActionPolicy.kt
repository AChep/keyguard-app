package com.artemchep.keyguard.feature.home.vault.screen

internal enum class VaultListWriteActionPolicy {
    Hide,
    Allow,
    ShowSubscription,
}

internal fun vaultListWriteActionPolicy(
    capability: WriteCapability,
    selectionActive: Boolean,
): VaultListWriteActionPolicy = when {
    selectionActive -> VaultListWriteActionPolicy.Hide
    capability == WriteCapability.Unknown -> VaultListWriteActionPolicy.Hide
    capability == WriteCapability.Allowed -> VaultListWriteActionPolicy.Allow
    else -> VaultListWriteActionPolicy.ShowSubscription
}
