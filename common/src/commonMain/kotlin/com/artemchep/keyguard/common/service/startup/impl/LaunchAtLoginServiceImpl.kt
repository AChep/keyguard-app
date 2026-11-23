package com.artemchep.keyguard.common.service.startup.impl

import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.startup.LaunchAtLoginRegistration
import com.artemchep.keyguard.common.service.startup.LaunchAtLoginService
import com.artemchep.keyguard.common.service.startup.LaunchAtLoginState
import com.artemchep.keyguard.common.service.startup.LaunchAtLoginStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serializes OS registration calls and publishes their result. A `null` registration
 * means this installation cannot register; a failed creation is retried on the next call.
 */
class LaunchAtLoginServiceImpl(
    private val createRegistration: suspend () -> LaunchAtLoginRegistration?,
    private val logRepository: LogRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LaunchAtLoginService {
    companion object {
        private const val TAG = "LaunchAtLogin"
    }

    private val mutableState = MutableStateFlow(LaunchAtLoginState())
    override val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var registration: LaunchAtLoginRegistration? = null

    override suspend fun refresh() {
        perform("read the status", busy = false) { status() }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun repairStale() {
        perform("read the status", busy = false) {
            val status = status()
            if (status != LaunchAtLoginStatus.STALE) return@perform status
            try {
                setEnabled(true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw StaleRepairException(error)
            }
        }
    }

    override suspend fun refreshIfObserved() {
        if (mutableState.subscriptionCount.value > 0) refresh()
    }

    override suspend fun setEnabled(enabled: Boolean) {
        perform(if (enabled) "enable" else "disable") { setEnabled(enabled) }
    }

    override suspend fun openSettings() {
        perform("open the settings") {
            openSettings()
            status()
        }
    }

    // Read and removal share one lock.
    override suspend fun removeForClearData(): Boolean = mutex.withLock {
        // Unreadable: nothing to remove.
        val status = performLocked("read the status", busy = false) { status() }
            ?: return@withLock true
        // A disabled entry needs no request: on Flatpak every change asks the portal.
        val registered = status.isEnabled || status == LaunchAtLoginStatus.STALE
        if (!status.canChange || !registered) return@withLock true
        performLocked("disable") { setEnabled(false) } != null
    }

    private suspend fun perform(
        name: String,
        busy: Boolean = true,
        operation: suspend LaunchAtLoginRegistration.() -> LaunchAtLoginStatus,
    ) {
        mutex.withLock { performLocked(name, busy, operation) }
    }

    /**
     * Publishes the [operation]'s status or failure. Returns the new status, or `null` on
     * failure. An OS rejection keeps the last observed state.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun performLocked(
        name: String,
        busy: Boolean = true,
        operation: suspend LaunchAtLoginRegistration.() -> LaunchAtLoginStatus,
    ): LaunchAtLoginStatus? {
        if (busy) mutableState.update { it.copy(busy = true, failed = false) }
        return try {
            val status = withContext(dispatcher) {
                val service = registration ?: createRegistration()?.also { registration = it }
                service?.operation() ?: LaunchAtLoginStatus.UNAVAILABLE
            }
            mutableState.value = LaunchAtLoginState(status = status)
            status
        } catch (error: CancellationException) {
            mutableState.update { it.copy(busy = false) }
            throw error
        } catch (error: StaleRepairException) {
            // The read succeeded: keep the entry visible, so the user can turn it off.
            fail("repair a stale entry", error.cause, status = LaunchAtLoginStatus.STALE)
            null
        } catch (error: Exception) {
            fail(name, error, status = null)
            null
        }
    }

    private fun fail(
        name: String,
        error: Throwable,
        status: LaunchAtLoginStatus?,
    ) {
        logRepository.post(TAG, "Failed to $name: ${error::class.simpleName}: ${error.message}", LogLevel.WARNING)
        mutableState.update { it.copy(status = status ?: it.status, busy = false, failed = true) }
    }

    /** The OS reported [LaunchAtLoginStatus.STALE], then registering the entry again failed. */
    private class StaleRepairException(override val cause: Exception) : Exception(cause)
}
