package com.artemchep.autotype

import com.artemchep.jna.DesktopLibJna
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val POWER_RESULT_UNSUPPORTED_PLATFORM: Int = -1

public enum class DesktopPowerEvent {
    DisplaySleep,
    DisplayWake,
    SystemSleep,
    SystemWake,
}

public interface DesktopPowerRegistration : AutoCloseable {
    public fun unregister(): Boolean

    override fun close() {
        unregister()
    }
}

public sealed interface DesktopPowerRegistrationResult {
    public data class Success(val registration: DesktopPowerRegistration) : DesktopPowerRegistrationResult
    public data class Failure(
        val reason: DesktopPowerRegistrationFailureReason,
        val cause: Throwable? = null,
    ) : DesktopPowerRegistrationResult
}

public enum class DesktopPowerRegistrationFailureReason {
    UnsupportedPlatform,
    InternalError,
}

// Keeps JNA callbacks reachable while native code may still invoke them.
private val powerCallbacks: MutableSet<DesktopLibJna.PowerEventCallback> =
    ConcurrentHashMap.newKeySet()

/**
 * Receives power events synchronously on the native notification thread.
 * Callbacks must finish promptly, must not unregister themselves, and must not
 * wait for AppKit/UI work. [onError] must also return promptly.
 */
public fun registerDesktopPowerEvents(
    onEvent: (DesktopPowerEvent) -> Unit,
    onError: (Throwable) -> Unit,
): DesktopPowerRegistrationResult = try {
    registerDesktopPowerEvents(DesktopLibJna.get(), onEvent, onError)
} catch (e: Throwable) {
    DesktopPowerRegistrationResult.Failure(DesktopPowerRegistrationFailureReason.InternalError, e)
}

internal fun registerDesktopPowerEvents(
    lib: DesktopLibJna,
    onEvent: (DesktopPowerEvent) -> Unit,
    onError: (Throwable) -> Unit,
    retention: MutableSet<DesktopLibJna.PowerEventCallback> = powerCallbacks,
): DesktopPowerRegistrationResult {
    val closed = AtomicBoolean(false)
    val callback = object : DesktopLibJna.PowerEventCallback {
        override fun invoke(event: Int) {
            if (closed.get()) return
            try {
                val typedEvent = when (event) {
                    1 -> DesktopPowerEvent.DisplaySleep
                    2 -> DesktopPowerEvent.DisplayWake
                    3 -> DesktopPowerEvent.SystemSleep
                    4 -> DesktopPowerEvent.SystemWake
                    else -> return
                }
                onEvent(typedEvent)
            } catch (e: Throwable) {
                // No JVM exception may escape through a C callback, including an
                // exception in the application's error handler.
                runCatching { onError(e) }
            }
        }
    }
    retention.add(callback)
    val id = try {
        lib.registerNativePowerEvents(callback)
    } catch (e: Throwable) {
        closed.set(true)
        // Registration may have reached native code. Keep the inert callback
        // alive when ownership is uncertain rather than risking a dangling FFI pointer.
        return DesktopPowerRegistrationResult.Failure(DesktopPowerRegistrationFailureReason.InternalError, e)
    }
    if (id <= 0) {
        closed.set(true)
        retention.remove(callback)
        val reason = if (id == POWER_RESULT_UNSUPPORTED_PLATFORM) {
            DesktopPowerRegistrationFailureReason.UnsupportedPlatform
        } else {
            DesktopPowerRegistrationFailureReason.InternalError
        }
        return DesktopPowerRegistrationResult.Failure(reason)
    }
    val registration = object : DesktopPowerRegistration {
        private var unregistered = false

        @Synchronized
        override fun unregister(): Boolean {
            if (unregistered) return false
            closed.set(true)
            // The callback never takes this object's monitor. Native removal
            // can therefore wait for AppKit without a callback/cleanup deadlock.
            val success = try {
                lib.unregisterNativePowerEvents(id)
            } catch (e: Throwable) {
                runCatching { onError(e) }
                false
            }
            if (success) {
                unregistered = true
                retention.remove(callback)
            }
            return success
        }
    }
    return DesktopPowerRegistrationResult.Success(registration)
}
