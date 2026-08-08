package com.artemchep.keyguard.ipctestclient.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A blocking bind to one Keyguard IPC service.
 *
 * Binds by explicit [ComponentName] rather than by action so a second Keyguard
 * install cannot silently take over the connection. [connect] blocks, so it must
 * not be called on the main thread.
 */
class IpcConnection<T : Any>(
    private val context: Context,
    val action: String,
    private val asInterface: (IBinder) -> T,
) : Closeable {
    class NotAvailableException(action: String) :
        IllegalStateException("No Keyguard provider answers $action")

    class BindTimeoutException(component: ComponentName) :
        IllegalStateException("Timed out binding to $component")

    private var connection: ServiceConnection? = null

    @Volatile
    var service: T? = null
        private set

    val isBound: Boolean get() = service != null

    fun provider(): IpcProviders.Provider? = IpcProviders.resolve(context, action)

    fun connect(timeoutMs: Long = DEFAULT_BIND_TIMEOUT_MS): T {
        service?.let { return it }
        val provider = provider() ?: throw NotAvailableException(action)
        val latch = CountDownLatch(1)
        var binder: IBinder? = null
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, value: IBinder?) {
                binder = value
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        val intent = Intent(action).apply { component = provider.component }
        check(context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
            "bindService refused ${provider.component}"
        }
        connection = serviceConnection
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            close()
            throw BindTimeoutException(provider.component)
        }
        val bound = asInterface(checkNotNull(binder))
        service = bound
        return bound
    }

    override fun close() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        service = null
    }

    companion object {
        const val DEFAULT_BIND_TIMEOUT_MS = 10_000L
    }
}
