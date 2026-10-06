package com.artemchep.keyguard.util.yubikey

import android.app.Activity
import android.content.Context
import android.view.KeyEvent
import com.yubico.yubikit.android.YubiKitManager
import com.yubico.yubikit.android.transport.nfc.NfcConfiguration
import com.yubico.yubikit.android.transport.nfc.NfcNotAvailable
import com.yubico.yubikit.android.transport.usb.UsbConfiguration
import com.yubico.yubikit.android.ui.OtpKeyListener
import com.yubico.yubikit.core.util.NdefUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/** The host owns foreground lifecycle and forwards keyboard events. */
class AndroidYubiKeyOtpReader(
    context: Context,
    onCaptureStarted: () -> Unit,
    private val onOtp: (Result<String>) -> Unit,
) {
    private val manager = YubiKitManager(context)
    private val ids = AtomicInteger()
    private val devices = MutableStateFlow<Map<Int, String>>(emptyMap())
    val usbDevices = devices.asStateFlow()
    private val listener = OtpKeyListener(object : OtpKeyListener.OtpListener {
        override fun onCaptureStarted() = onCaptureStarted.invoke()
        override fun onCaptureComplete(credentials: String) = onOtp(Result.success(credentials))
    })

    fun onKeyEvent(event: KeyEvent): Boolean = listener.onKeyEvent(event)

    fun startUsb(): Boolean = try {
        manager.startUsbDiscovery(UsbConfiguration()) { device ->
            val id = ids.incrementAndGet()
            devices.update { it + (id to device.pid.name) }
            device.setOnClosed { devices.update { it - id } }
        }
        true
    } catch (_: Exception) {
        false
    }

    fun stopUsb() {
        manager.stopUsbDiscovery()
        devices.value = emptyMap()
    }

    fun startNfc(activity: Activity): Boolean = try {
        manager.startNfcDiscovery(NfcConfiguration(), activity) { device ->
            onOtp(runCatching { NdefUtils.getNdefPayload(device.readNdef()) })
        }
        true
    } catch (_: NfcNotAvailable) {
        false
    }

    fun stopNfc(activity: Activity) = manager.stopNfcDiscovery(activity)
}
