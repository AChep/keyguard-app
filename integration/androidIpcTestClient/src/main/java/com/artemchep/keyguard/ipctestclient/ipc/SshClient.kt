package com.artemchep.keyguard.ipctestclient.ipc

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.openintents.ssh.authentication.ISshAuthenticationService

/**
 * The raw SSH Authentication transport: a single `execute(Intent)` binder call.
 *
 * Bypasses [org.openintents.ssh.authentication.SshAuthenticationApi] for the
 * same reason [OpenPgpClient] bypasses its wrapper - that class overwrites the
 * API version extra with its own constant.
 */
class SshClient(
    private val context: Context,
    private val service: ISshAuthenticationService,
) {
    class Call(
        val request: Intent,
        val result: Intent,
        val durationMs: Long,
    )

    fun execute(request: Intent): Call {
        val startedAt = SystemClock.elapsedRealtime()
        val result = service.execute(request)
        val durationMs = SystemClock.elapsedRealtime() - startedAt
        result.setExtrasClassLoader(context.classLoader)
        return Call(request = request, result = result, durationMs = durationMs)
    }
}
