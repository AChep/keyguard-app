package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.URL_GITHUB
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.common.model.AppVersionLog
import com.artemchep.keyguard.common.usecase.GetAppBuildDate
import com.artemchep.keyguard.common.usecase.GetAppBuildRef
import com.artemchep.keyguard.common.usecase.GetVersionLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

internal class AppInformationController(
    private val ctx: CoreContext,
) {
    private val getAppBuildDate: GetAppBuildDate by lazy { ctx.koin.get() }
    private val getAppBuildRef: GetAppBuildRef by lazy { ctx.koin.get() }
    private val getVersionLog: GetVersionLog by lazy { ctx.koin.get() }

    fun observeAppInformation(
        onChange: (AppInformationSnapshot) -> Unit,
    ): KeyguardCancellable = ctx.launchObserver {
        informationFlow().collectOnMain { onChange(it) }
    }

    suspend fun loadAppInformation(): AppInformationSnapshot = informationFlow().first()

    private fun informationFlow() = appInformationFlow(
        buildDate = getAppBuildDate(),
        buildRef = getAppBuildRef(),
        versionLog = getVersionLog(),
    )
}

internal fun appInformationFlow(
    buildDate: Flow<String>,
    buildRef: Flow<String>,
    versionLog: Flow<List<AppVersionLog>>,
): Flow<AppInformationSnapshot> = combine(buildDate, buildRef, versionLog) { date, ref, log ->
    val currentRef = ref.takeIf(::isAvailableBuildRef)
    // Older Apple builds recorded the literal "unknown". Ignore those entries
    // without modifying shared history or producing a broken comparison URL.
    val refs = log.map { it.ref }
        .filter(::isAvailableBuildRef)
        .distinct()
        .take(2)
    val newRef = refs.getOrNull(0)
    val oldRef = refs.getOrNull(1)
    AppInformationSnapshot(
        loaded = true,
        buildDate = date,
        buildRef = currentRef,
        buildRefUrl = currentRef?.let { "$URL_GITHUB/tree/$it" },
        changelogText = if (oldRef != null) "$newRef...$oldRef" else null,
        changelogUrl = if (oldRef != null) "$URL_GITHUB/compare/$oldRef...$newRef" else null,
    )
}.distinctUntilChanged()

private fun isAvailableBuildRef(ref: String): Boolean =
    ref.isNotBlank() && !ref.equals("unknown", ignoreCase = true)
