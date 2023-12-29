package com.artemchep.keyguard.feature.yubikey

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.platform.LeUsbPid
import kotlinx.collections.immutable.ImmutableSet

@Immutable
data class YubiKeyUsbState(
    val enabled: Boolean,
    val capturing: Boolean,
    val devices: ImmutableSet<LeUsbPid>,
)

@Immutable
data class YubiKeyNfcState(
    val enabled: Boolean,
)

@Stable
data class YubiKeyState(
    val usbState: State<Loadable<YubiKeyUsbState>>,
    val nfcState: State<Loadable<YubiKeyNfcState>>,
)
