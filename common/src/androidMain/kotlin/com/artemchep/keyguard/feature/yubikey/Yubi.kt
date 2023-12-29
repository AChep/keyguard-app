package com.artemchep.keyguard.feature.yubikey

import android.content.Context
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.android.closestActivityOrNull
import com.artemchep.keyguard.common.model.Loadable
import com.yubico.yubikit.android.YubiKitManager
import com.yubico.yubikit.android.transport.nfc.NfcConfiguration
import com.yubico.yubikit.android.transport.nfc.NfcNotAvailable
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.android.transport.usb.UsbConfiguration
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.android.ui.OtpKeyListener
import com.yubico.yubikit.android.ui.OtpKeyListener.OtpListener
import com.yubico.yubikit.core.UsbPid
import com.yubico.yubikit.core.util.Callback
import com.yubico.yubikit.core.util.NdefUtils
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableSet
import java.io.IOException
import java.util.UUID

@Composable
actual fun rememberYubiKey(
    send: OnYubiKeyListener?,
): YubiKeyState {
    val context = LocalContext.current
    val kit = remember(context) {
        YubiKitManager(context)
    }

    val usbState = kit.rememberYubiKeyUsbState(
        context = context,
        send = send,
    )
    val nfcState = kit.rememberYubiKeyNfcState(
        context = context,
        send = send,
    )

    return remember(usbState, nfcState) {
        YubiKeyState(
            usbState = usbState,
            nfcState = nfcState,
        )
    }
}

@Composable
private fun YubiKitManager.rememberYubiKeyUsbState(
    context: Context,
    send: OnYubiKeyListener?,
): State<Loadable<YubiKeyUsbState>> {
    data class ActiveDevice(
        val id: String,
        val pid: UsbPid,
    )

    val devicesState = remember {
        mutableStateOf<PersistentMap<String, ActiveDevice>>(persistentMapOf())
    }
    val loadedState = remember {
        mutableStateOf(false)
    }
    val enabledState = remember {
        mutableStateOf(false)
    }
    val capturingState = remember {
        mutableStateOf(false)
    }

    val updatedHaptic = rememberUpdatedState(newValue = LocalHapticFeedback.current)
    val updatedSend = rememberUpdatedState(newValue = send)
    val otpKeyListener = remember {
        OtpKeyListener(
            object : OtpListener {
                override fun onCaptureStarted() {
                    capturingState.value = true
                }

                override fun onCaptureComplete(credentials: String) {
                    val event = credentials.right()
                    updatedSend.value?.invoke(event)

                    capturingState.value = false
                    // Notify user that something had happened, it kinda
                    // makes him fill that it worked.
                    updatedHaptic.value.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            },
        )
    }
    val requester = remember {
        FocusRequester()
    }
    Spacer(
        modifier = Modifier
            .onKeyEvent { event ->
                otpKeyListener.onKeyEvent(event.nativeKeyEvent)
            }
            .focusRequester(requester)
            .focusable(),
    )
    LaunchedEffect(requester) {
        requester.requestFocus()
    }

    DisposableEffect(context, this) {
        val callback = Callback<UsbYubiKeyDevice> { device: UsbYubiKeyDevice ->
            val info = ActiveDevice(
                id = UUID.randomUUID().toString(),
                pid = device.pid,
            )

            // Add device to the state.
            kotlin.run {
                val newState = devicesState.value.builder().apply {
                    put(info.id, info)
                }.build()
                devicesState.value = newState
            }
            device.setOnClosed {
                // Remove device from the state.
                val newState = devicesState.value.builder().apply {
                    remove(info.id)
                }.build()
                devicesState.value = newState
            }
        }
        try {
            val config = UsbConfiguration()
            startUsbDiscovery(config, callback)
        } catch (e: Exception) {
            // Since we did not start USB discovery, we do not
            // want to stop it either.
            loadedState.value = true
            enabledState.value = false
            return@DisposableEffect onDispose {
                // Do nothing.
            }
        }
        loadedState.value = true
        enabledState.value = true
        onDispose {
            stopUsbDiscovery()
        }
    }

    return remember(
        loadedState,
        enabledState,
        capturingState,
        devicesState,
    ) {
        derivedStateOf {
            val loaded = loadedState.value
            if (!loaded) return@derivedStateOf Loadable.Loading

            val enabled = enabledState.value
            val capturing = capturingState.value
            val devices = devicesState.value
                .values
                .asSequence()
                .map { it.pid }
                .toImmutableSet()
            val state = YubiKeyUsbState(
                enabled = enabled,
                capturing = capturing,
                devices = devices,
            )
            Loadable.Ok(state)
        }
    }
}

@Composable
private fun YubiKitManager.rememberYubiKeyNfcState(
    context: Context,
    send: OnYubiKeyListener?,
): State<Loadable<YubiKeyNfcState>> {
    val state = remember {
        mutableStateOf<Loadable<YubiKeyNfcState>>(Loadable.Loading)
    }

    val updatedSend = rememberUpdatedState(newValue = send)
    DisposableEffect(context, this) {
        val activity = requireNotNull(context.closestActivityOrNull)
        val callback = Callback<NfcYubiKeyDevice> { device: NfcYubiKeyDevice ->
            val event = try {
                val credential = NdefUtils.getNdefPayload(device.readNdef())
                credential.right()
            } catch (e: IOException) {
                // Failed to read the credentials.
                e.left()
            }
            updatedSend.value?.invoke(event)
        }
        try {
            val config = NfcConfiguration()
            startNfcDiscovery(config, activity, callback)
        } catch (e: NfcNotAvailable) {
            // Since we did not start NFC discovery, we do not
            // want to stop it either.
            state.value = Loadable.Ok(YubiKeyNfcState(enabled = false))
            return@DisposableEffect onDispose {
                // Do nothing.
            }
        }
        state.value = Loadable.Ok(YubiKeyNfcState(enabled = true))
        onDispose {
            stopNfcDiscovery(activity)
        }
    }

    return state
}
