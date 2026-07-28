package com.artemchep.keyguard.common.service.gpgkeyserver.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.RefreshGpgPublicKeysRequest
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverRefreshWorker
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverAutoRefresh
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverLastRefresh
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverRefreshInterval
import com.artemchep.keyguard.common.usecase.RefreshGpgPublicKeys
import com.artemchep.keyguard.common.service.gpgkeyserver.gpgKeyserverRefreshFingerprintOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

class GpgKeyserverRefreshWorkerImpl(
    private val getGpgKeyserverAutoRefresh: GetGpgKeyserverAutoRefresh,
    private val getGpgKeyserverRefreshInterval: GetGpgKeyserverRefreshInterval,
    private val getGpgKeyserverLastRefresh: GetGpgKeyserverLastRefresh,
    private val getCiphers: GetCiphers,
    private val refreshGpgPublicKeys: RefreshGpgPublicKeys,
    private val logRepository: LogRepository,
    private val now: () -> Instant = { Clock.System.now() },
) : GpgKeyserverRefreshWorker {
    companion object {
        private const val TAG = "GpgKeyserverRefreshWorker"
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        getGpgKeyserverAutoRefresh = directDI.instance(),
        getGpgKeyserverRefreshInterval = directDI.instance(),
        getGpgKeyserverLastRefresh = directDI.instance(),
        getCiphers = directDI.instance(),
        refreshGpgPublicKeys = directDI.instance(),
        logRepository = directDI.instance(),
    )

    override fun launch(scope: CoroutineScope): Job = scope.launch {
        combine(
            getGpgKeyserverAutoRefresh(),
            getGpgKeyserverRefreshInterval(),
        ) { enabled, interval ->
            enabled to interval
        }
            .distinctUntilChanged()
            .collectLatest { (enabled, interval) ->
                if (!enabled || interval <= Duration.ZERO) {
                    return@collectLatest
                }

                while (coroutineContext.isActive) {
                    val lastRefresh = getGpgKeyserverLastRefresh().first()
                    val currentTime = now()
                    val dueAt = lastRefresh?.plus(interval)
                    if (dueAt == null || dueAt <= currentTime) {
                        attemptRefresh()
                        // The refresh may have been a no-op (e.g. an empty vault
                        // writes no timestamp), so always wait a full interval
                        // afterwards to avoid a tight loop.
                        delay(interval)
                    } else {
                        // Wait until the refresh is due, then loop and re-read
                        // the timestamp - it may have changed via a manual
                        // refresh in the meantime.
                        delay(dueAt - currentTime)
                    }
                }
            }
    }

    private suspend fun attemptRefresh() {
        try {
            val cipherIds = getCiphers()
                .first()
                .filter { it.gpgKeyserverRefreshFingerprintOrNull() != null }
                .map { it.id }
                .toSet()
            if (cipherIds.isEmpty()) {
                return
            }

            // The refresh use-case writes the last-refresh timestamp itself
            // upon completion.
            refreshGpgPublicKeys(
                RefreshGpgPublicKeysRequest(
                    cipherIds = cipherIds,
                ),
            ).bind()
        } catch (e: Exception) {
            e.throwIfFatalOrCancellation()
            logRepository.post(
                tag = TAG,
                message = "Failed to auto-refresh GPG public keys: ${e.message}",
                level = LogLevel.WARNING,
            )
        }
    }
}
