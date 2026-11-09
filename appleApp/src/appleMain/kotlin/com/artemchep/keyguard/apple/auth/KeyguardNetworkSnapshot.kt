package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.apple.model.TextFieldSnapshot
import com.artemchep.keyguard.apple.model.toFieldSnapshot
import com.artemchep.keyguard.feature.auth.keyguard.KeyguardNetworkState
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardRelayDefaults

/**
 * How this device reaches the other devices, as [KeyguardNetworkState]. The
 * owning session takes the text of a field with `setNetworkField(id, text)`.
 */
data class KeyguardNetworkSnapshot(
    val relayUrl: TextFieldSnapshot,
    val iceUrl: TextFieldSnapshot,
    val accessToken: TextFieldSnapshot,
    val allowLan: Boolean,
    val relayStorage: Boolean,
) {
    companion object {
        val empty = KeyguardNetworkSnapshot(
            relayUrl = TextFieldSnapshot.empty(FIELD_RELAY_URL),
            iceUrl = TextFieldSnapshot.empty(FIELD_ICE_URL),
            accessToken = TextFieldSnapshot.empty(FIELD_ACCESS_TOKEN),
            allowLan = true,
            relayStorage = true,
        )
    }
}

private const val FIELD_RELAY_URL = "keyguard.network.relayUrl"
private const val FIELD_ICE_URL = "keyguard.network.iceUrl"
private const val FIELD_ACCESS_TOKEN = "keyguard.network.accessToken"

/** Also registers the live callbacks of the text fields into [fieldHandlers], by their ids. */
internal fun KeyguardNetworkState.toSnapshot(
    fieldHandlers: MutableMap<String, (String) -> Unit>,
) = KeyguardNetworkSnapshot(
    // An empty field means the default server.
    relayUrl = relayUrl.toFieldSnapshot(
        fieldHandlers,
        id = FIELD_RELAY_URL,
        placeholder = KeyguardRelayDefaults.RELAY_URL,
    ),
    iceUrl = iceUrl.toFieldSnapshot(
        fieldHandlers,
        id = FIELD_ICE_URL,
        placeholder = KeyguardRelayDefaults.ICE_URL,
    ),
    accessToken = accessToken.toFieldSnapshot(fieldHandlers, id = FIELD_ACCESS_TOKEN),
    allowLan = allowLan.checked,
    relayStorage = relayStorage.checked,
)

internal fun KeyguardNetworkState.setAllowLan(value: Boolean) {
    allowLan.onChange?.invoke(value)
}

internal fun KeyguardNetworkState.setRelayStorage(value: Boolean) {
    relayStorage.onChange?.invoke(value)
}
