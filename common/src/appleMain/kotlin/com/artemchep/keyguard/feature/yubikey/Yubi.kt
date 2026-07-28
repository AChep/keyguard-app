package com.artemchep.keyguard.feature.yubikey

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.artemchep.keyguard.common.model.Loadable
import kotlinx.collections.immutable.persistentSetOf

@Composable
actual fun rememberYubiKey(
    send: OnYubiKeyListener?,
): YubiKeyState = remember {
    val usbState = run {
        val value = YubiKeyUsbState(
            enabled = false,
            capturing = false,
            devices = persistentSetOf(),
        )
        mutableStateOf<Loadable<YubiKeyUsbState>>(Loadable.Ok(value))
    }
    val nfcState = run {
        val value = YubiKeyNfcState(
            enabled = false,
        )
        mutableStateOf<Loadable<YubiKeyNfcState>>(Loadable.Ok(value))
    }
    YubiKeyState(
        usbState = usbState,
        nfcState = nfcState,
    )
}
