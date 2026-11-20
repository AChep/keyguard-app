package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.build.BuildKonfig
import com.artemchep.keyguard.common.model.AppSemVer
import com.artemchep.keyguard.common.model.AppUpdateLevel
import com.artemchep.keyguard.common.model.DAppUpdate
import com.artemchep.keyguard.common.service.flavor.AppDistribution
import com.artemchep.keyguard.common.service.release.nextAppUpdateChangeAt
import com.artemchep.keyguard.common.service.release.parseAppBuildDate
import com.artemchep.keyguard.common.service.release.selectAppUpdate
import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAppUpdate
import com.artemchep.keyguard.common.usecase.GetAppUpdateDelay
import com.artemchep.keyguard.common.usecase.GetAppUpdateLevel
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformLatest
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class GetAppUpdateImpl(
    settingsReadRepository: SettingsReadRepository,
    getAppUpdateLevel: GetAppUpdateLevel,
    getAppUpdateDelay: GetAppUpdateDelay,
    appDistribution: AppDistribution,
    versionName: String = BuildKonfig.versionName,
    buildDate: String = BuildKonfig.buildDate,
    hasBrowser: Boolean = CurrentPlatform.hasBrowser(),
    scope: CoroutineScope = GlobalScope,
    private val now: () -> Instant = { Clock.System.now() },
) : GetAppUpdate {
    private val sharedFlow: Flow<DAppUpdate?> = run {
        // An unknown installed version can not be compared,
        // so the app never claims there is an update.
        val installedVersion = AppSemVer.parseOrNull(versionName)
            ?: return@run flowOf(null)
        val installedBuildDate = parseAppBuildDate(buildDate)

        val releasesFlow = settingsReadRepository.getAppUpdateCache()
            .map { cache -> cache?.releases.orEmpty() }
            .distinctUntilChanged()
        combine(
            releasesFlow,
            getAppUpdateLevel(),
            getAppUpdateDelay(),
        ) { releases, level, minAge ->
            Triple(releases, level, minAge)
        }
            .transformLatest { (releases, level, minAge) ->
                do {
                    val currentTime = now()
                    val update = selectAppUpdate(
                        releases = releases,
                        installedVersion = installedVersion,
                        installedBuildDate = installedBuildDate,
                        level = level,
                        delay = minAge,
                        now = currentTime,
                    )
                    emit(update)

                    // Pick again once a cached release is
                    // public for long enough.
                    val nextChangeAt = nextAppUpdateChangeAt(
                        releases = releases,
                        delay = minAge,
                        now = currentTime,
                    ).takeIf { level != AppUpdateLevel.OFF }
                    if (nextChangeAt != null) {
                        delay(nextChangeAt - currentTime)
                    }
                } while (nextChangeAt != null)
            }
            .map { update ->
                update?.copy(
                    url = resolveUrl(
                        releaseUrl = update.url,
                        distribution = appDistribution,
                        hasBrowser = hasBrowser,
                    ),
                )
            }
            .distinctUntilChanged()
    }.shareIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000L),
        replay = 1,
    )

    override fun invoke() = sharedFlow
}

/**
 * On store builds the store delivers the update, so the app links
 * to the store listing instead of GitHub. Returns `null` if the
 * platform can not open a link.
 */
private fun resolveUrl(
    releaseUrl: String?,
    distribution: AppDistribution,
    hasBrowser: Boolean,
): String? {
    if (!hasBrowser) {
        return null
    }
    return if (distribution.isStore) distribution.storeUrl else releaseUrl
}
