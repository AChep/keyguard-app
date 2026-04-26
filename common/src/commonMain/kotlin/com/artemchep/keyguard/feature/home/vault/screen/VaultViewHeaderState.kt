package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.send.view.SendViewState

@Immutable
data class VaultViewHeaderState(
    val isLoading: Boolean,
    val icon: VaultItemIcon?,
    val name: String?,
    val accentLight: Color,
    val accentDark: Color,
    val hasRemoteService: Boolean,
)

fun VaultViewState.toVaultViewHeaderState(): VaultViewHeaderState {
    val cipher = content as? VaultViewState.Content.Cipher
    return VaultViewHeaderState(
        isLoading = content is VaultViewState.Content.Loading,
        icon = cipher?.icon,
        name = cipher?.data?.name,
        accentLight = cipher?.data?.accentLight ?: Color.Transparent,
        accentDark = cipher?.data?.accentDark ?: Color.Transparent,
        hasRemoteService = cipher?.data?.service?.remote != null,
    )
}

fun SendViewState.toVaultViewHeaderState(): VaultViewHeaderState {
    val cipher = content as? SendViewState.Content.Cipher
    return VaultViewHeaderState(
        isLoading = content is SendViewState.Content.Loading,
        icon = cipher?.icon,
        name = cipher?.data?.name,
        accentLight = cipher?.data?.accentLight ?: Color.Transparent,
        accentDark = cipher?.data?.accentDark ?: Color.Transparent,
        hasRemoteService = cipher?.data?.service?.remote != null,
    )
}
