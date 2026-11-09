package com.artemchep.keyguard.feature.passkeyhybrid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import com.artemchep.keyguard.util.fidohybrid.HybridAdvertiser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** The screen obtains Nearby devices permission before this adapter is called. */
@SuppressLint("MissingPermission")
internal class AndroidHybridAdvertiser(
    context: Context,
) : HybridAdvertiser {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var stopAdvertising: (() -> Unit)? = null

    override suspend fun start(payload: ByteArray) {
        require(payload.size == PAYLOAD_BYTES)
        val bluetooth = checkNotNull(adapter)
        check(bluetooth.isEnabled)
        val advertiser = checkNotNull(bluetooth.bluetoothLeAdvertiser)
        val started = CompletableDeferred<Unit>()
        val callback =
            object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    started.complete(Unit)
                }

                override fun onStartFailure(errorCode: Int) {
                    started.completeExceptionally(IllegalStateException("BLE advertising failed"))
                }
            }
        val settings =
            AdvertiseSettings
                .Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(false)
                .setTimeout(TRANSACTION_TIMEOUT_MS)
                .build()
        val uuid = ParcelUuid(UUID.fromString("0000fff9-0000-1000-8000-00805f9b34fb"))
        val data =
            AdvertiseData
                .Builder()
                .addServiceUuid(uuid)
                .addServiceData(uuid, payload)
                .setIncludeDeviceName(false)
                .build()
        stopAdvertising = { advertiser.stopAdvertising(callback) }
        var successful = false
        try {
            advertiser.startAdvertising(settings, data, callback)
            withTimeout(START_TIMEOUT_MS) { started.await() }
            successful = true
        } finally {
            if (!successful) stop()
        }
    }

    override fun stop() {
        val stop = stopAdvertising
        stopAdvertising = null
        try {
            stop?.invoke()
        } catch (_: SecurityException) {
            // Permission may have been revoked while the transaction was running.
        }
    }
}

private const val PAYLOAD_BYTES = 20
private const val TRANSACTION_TIMEOUT_MS = 120_000
private const val START_TIMEOUT_MS = 5000L
