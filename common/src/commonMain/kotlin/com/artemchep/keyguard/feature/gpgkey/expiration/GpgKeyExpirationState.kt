package com.artemchep.keyguard.feature.gpgkey.expiration

data class GpgKeyExpirationState(
    val presets: List<Preset> = emptyList(),
    val components: List<Component> = emptyList(),
    val validationError: String? = null,
    val onDeny: (() -> Unit)? = null,
    val onConfirm: (() -> Unit)? = null,
) {
    data class Preset(
        val key: String,
        val title: String,
        val text: String?,
        val selected: Boolean,
        val onClick: () -> Unit,
    )

    data class Component(
        val key: String,
        val title: String,
        val text: String,
        val selected: Boolean,
        val onToggle: () -> Unit,
    )
}
