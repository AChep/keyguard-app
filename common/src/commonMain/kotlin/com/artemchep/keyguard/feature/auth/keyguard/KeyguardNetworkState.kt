package com.artemchep.keyguard.feature.auth.keyguard

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.feature.auth.common.SwitchFieldModel
import com.artemchep.keyguard.feature.auth.common.TextFieldModel

/** How this device reaches the other devices. */
@Immutable
data class KeyguardNetworkState(
    val relayUrl: TextFieldModel,
    val iceUrl: TextFieldModel,
    val accessToken: TextFieldModel,
    val allowLan: SwitchFieldModel,
    /** Lets the relay keep the state of the group for the devices that are offline. */
    val relayStorage: SwitchFieldModel,
)
