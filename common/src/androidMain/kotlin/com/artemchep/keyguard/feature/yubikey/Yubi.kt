package com.artemchep.keyguard.feature.yubikey

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.LifecycleResumeEffect
import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.android.closestActivityOrNull
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.util.yubikey.AndroidYubiKeyOtpReader
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.launch

@Composable
actual fun rememberYubiKey(send: OnYubiKeyListener?): YubiKeyState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = rememberUpdatedState(LocalHapticFeedback.current)
    val onResult = rememberUpdatedState(send)
    val capturing = remember { mutableStateOf(false) }
    val reader = remember(context) {
        AndroidYubiKeyOtpReader(
            context = context,
            onCaptureStarted = { capturing.value = true },
            onOtp = { result ->
                scope.launch {
                    capturing.value = false
                    onResult.value?.invoke(result.fold(onSuccess = { it.right() }, onFailure = { it.left() }))
                    if (result.isSuccess) haptic.value.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            },
        )
    }
    val devices = reader.usbDevices.collectAsState()
    val usbEnabled = remember { mutableStateOf(false) }
    val nfcState = remember { mutableStateOf<Loadable<YubiKeyNfcState>>(Loadable.Loading) }
    DisposableEffect(reader) {
        val started = reader.startUsb()
        usbEnabled.value = started
        onDispose { if (started) reader.stopUsb() }
    }
    LifecycleResumeEffect(reader) {
        val activity = requireNotNull(context.closestActivityOrNull)
        val enabled = reader.startNfc(activity)
        nfcState.value = Loadable.Ok(YubiKeyNfcState(enabled))
        onPauseOrDispose { if (enabled) reader.stopNfc(activity) }
    }
    val requester = remember { FocusRequester() }
    Spacer(Modifier.onKeyEvent { reader.onKeyEvent(it.nativeKeyEvent) }.focusRequester(requester).focusable())
    LaunchedEffect(requester) { requester.requestFocus() }
    val usbState = remember(reader) {
        derivedStateOf<Loadable<YubiKeyUsbState>> {
            Loadable.Ok(YubiKeyUsbState(usbEnabled.value, capturing.value, devices.value.values.toImmutableSet()))
        }
    }
    return remember(usbState, nfcState) { YubiKeyState(usbState, nfcState) }
}
