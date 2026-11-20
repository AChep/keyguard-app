package com.artemchep.keyguard.common.service.release.impl

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.AppReleaseCache
import com.artemchep.keyguard.common.model.AppUpdateLevel
import com.artemchep.keyguard.common.model.DAppUpdate
import com.artemchep.keyguard.common.model.DNotification
import com.artemchep.keyguard.common.model.DNotificationChannel
import com.artemchep.keyguard.common.model.DNotificationId
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.release.AppReleaseRepository
import com.artemchep.keyguard.common.service.release.AppUpdateWorker
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.GetAppUpdate
import com.artemchep.keyguard.common.usecase.GetAppUpdateLevel
import com.artemchep.keyguard.common.usecase.ShowNotification
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.update_available_title
import com.artemchep.keyguard.res.update_available_version_text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class AppUpdateWorkerImpl(
    private val getAppUpdateLevel: GetAppUpdateLevel,
    private val getAppUpdate: GetAppUpdate,
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
    private val appReleaseRepository: AppReleaseRepository,
    private val showNotification: ShowNotification,
    private val logRepository: LogRepository,
    private val notificationFactory: suspend (DAppUpdate) -> DNotification,
    private val now: () -> Instant = { Clock.System.now() },
) : AppUpdateWorker {
    companion object {
        private const val TAG = "AppUpdateWorker"

        internal val INITIAL_DELAY = 15.seconds
        internal val CHECK_INTERVAL = 24.hours
        internal val RETRY_INTERVAL = 1.hours
    }

    override fun launch(scope: CoroutineScope): Job = scope.launch {
        launch {
            checkForUpdates()
        }
        launch {
            notifyAboutUpdates()
        }
    }

    private suspend fun checkForUpdates() {
        // Let the app load fully and do not compete for the
        // resources.
        delay(INITIAL_DELAY)
        getAppUpdateLevel()
            .map { level -> level != AppUpdateLevel.OFF }
            .distinctUntilChanged()
            .collectLatest { enabled ->
                if (!enabled) {
                    return@collectLatest
                }

                var lastCheckedAt = settingsReadWriteRepository.getAppUpdateCache()
                    .first()
                    ?.lastCheckedAt
                while (true) {
                    val wait = timeUntilNextCheck(lastCheckedAt)
                    if (wait > Duration.ZERO) {
                        delay(wait)
                        continue
                    }

                    val checkedAt = refresh()
                    if (checkedAt != null) {
                        lastCheckedAt = checkedAt
                    } else {
                        delay(RETRY_INTERVAL)
                    }
                }
            }
    }

    private fun timeUntilNextCheck(
        lastCheckedAt: Instant?,
    ): Duration {
        if (lastCheckedAt == null) {
            return Duration.ZERO
        }
        val elapsed = now() - lastCheckedAt
        // The clock moved backwards: check now rather
        // than wait for longer than an interval.
        return if (elapsed < Duration.ZERO) {
            Duration.ZERO
        } else {
            CHECK_INTERVAL - elapsed
        }
    }

    /**
     * Fetches the releases and caches them. Returns the time
     * of the check, or `null` if it failed.
     */
    private suspend fun refresh(): Instant? = appReleaseRepository
        .getReleases()
        .flatMap { releases ->
            val cache = AppReleaseCache(
                lastCheckedAt = now(),
                releases = releases,
            )
            settingsReadWriteRepository.setAppUpdateCache(cache)
                .map { cache.lastCheckedAt }
        }
        .attempt()
        .bind()
        .fold(
            ifLeft = { e ->
                logRepository.post(
                    tag = TAG,
                    message = "Failed to check for app updates: ${e.message}",
                    level = LogLevel.WARNING,
                )
                null
            },
            ifRight = { checkedAt ->
                checkedAt
            },
        )

    private suspend fun notifyAboutUpdates() {
        getAppUpdate()
            .filterNotNull()
            .collect { update ->
                val notifiedTag = settingsReadWriteRepository.getAppUpdateNotifiedTag()
                    .first()
                if (notifiedTag == update.eligibleTag) {
                    return@collect
                }

                // Compose string resources must not load on
                // the Android main thread.
                ioEffect(Dispatchers.Default) { notificationFactory(update) }
                    .flatMap { notification ->
                        showNotification(notification)
                    }
                    .flatMap {
                        settingsReadWriteRepository.setAppUpdateNotifiedTag(update.eligibleTag)
                    }
                    .attempt()
                    .bind()
                    .onLeft { e ->
                        logRepository.post(
                            tag = TAG,
                            message = "Failed to notify about the app update: ${e.message}",
                            level = LogLevel.WARNING,
                        )
                    }
            }
    }
}

/**
 * Creates the notification about the [update]. A tap opens its
 * link, if it has one: the release page or the store listing.
 */
suspend fun createAppUpdateNotification(
    update: DAppUpdate,
    context: LeContext,
): DNotification = DNotification(
    id = DNotificationId.APP_UPDATE,
    title = textResource(Res.string.update_available_title, context),
    text = textResource(Res.string.update_available_version_text, context, update.version),
    channel = DNotificationChannel.APP_UPDATES,
    url = update.url,
)
