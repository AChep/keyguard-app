package com.yubico.yubikit.android.ui

import com.artemchep.keyguard.util.yubikey.YubiKeyException
import com.artemchep.keyguard.util.yubikey.YubiKeyFailure
import com.artemchep.keyguard.util.yubikey.YubiKeyOperation
import com.yubico.yubikit.core.application.CommandState
import com.yubico.yubikit.core.otp.OtpConnection
import com.yubico.yubikit.yubiotp.YubiOtpSession
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KeyguardYubiKeyActionTest {
    @Test
    fun cancellationWhileOpeningSessionPreventsProvisioning() {
        for (slot in 1..2) {
            for (overwrite in listOf(false, true)) {
                val commandState = CommandState()
                val connection = WriteTrackingConnection(
                    configured = overwrite,
                    onRead = commandState::cancel,
                )
                val error = assertFailsWith<YubiKeyException> {
                    YubiOtpSession(connection).use { session ->
                        executeYubiKeyOperation(session, provision(slot, overwrite), commandState)
                    }
                }

                assertEquals(YubiKeyFailure.CANCELED, error.failure)
                assertTrue(connection.writes.isEmpty())
                assertTrue(connection.closed)
            }
        }
    }

    @Test
    fun activeProvisioningStillReachesTheDevice() {
        val connection = WriteTrackingConnection(configured = false)
        assertFailsWith<IOException> {
            YubiOtpSession(connection).use { session ->
                executeYubiKeyOperation(session, provision(2, false), CommandState())
            }
        }

        assertEquals(1, connection.writes.size)
        assertTrue(connection.closed)
    }

    private fun provision(slot: Int, overwrite: Boolean) = YubiKeyOperation.Provision(
        slot = slot,
        challenge = byteArrayOf(1),
        secret = ByteArray(20) { 9 },
        overwrite = overwrite,
    )
}

private class WriteTrackingConnection(
    private val configured: Boolean,
    private val onRead: () -> Unit = {},
) : OtpConnection {
    val writes = mutableListOf<ByteArray>()
    var closed = false
        private set

    override fun receive(report: ByteArray) {
        // YubiKit reads firmware and slot status while opening the session.
        byteArrayOf(0, 5, 7, 3, 42, if (configured) 3 else 0, 0, 0).copyInto(report)
        onRead()
    }

    override fun send(report: ByteArray) {
        writes += report.copyOf()
        throw IOException("Stop at the first device write.")
    }

    override fun close() {
        closed = true
    }
}
