package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.model.SettingOptionSnapshot
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AppUpdateLevel
import com.artemchep.keyguard.common.usecase.GetAppUpdate
import com.artemchep.keyguard.common.usecase.GetAppUpdateDelay
import com.artemchep.keyguard.common.usecase.GetAppUpdateDelayVariants
import com.artemchep.keyguard.common.usecase.GetAppUpdateLevel
import com.artemchep.keyguard.common.usecase.PutAppUpdateDelay
import com.artemchep.keyguard.common.usecase.PutAppUpdateLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Duration

/** Global preferences, so no session is required. */
internal class AppUpdateController(
    private val getAppUpdate: GetAppUpdate,
    private val getAppUpdateLevel: GetAppUpdateLevel,
    private val putAppUpdateLevel: PutAppUpdateLevel,
    private val getAppUpdateDelay: GetAppUpdateDelay,
    private val getAppUpdateDelayVariants: GetAppUpdateDelayVariants,
    private val putAppUpdateDelay: PutAppUpdateDelay,
    private val scope: CoroutineScope,
    private val text: suspend (StringResource) -> String,
    private val formatDelay: suspend (Duration) -> String,
) {
    /** Main-confined: written when a snapshot is published, read by [setDelay]. */
    private var latestDelayVariants: List<Duration> = emptyList()

    /** Emits `null` when there is no newer release to show. */
    fun observeAppUpdate(
        onChange: (AppUpdateSnapshot?) -> Unit,
    ): KeyguardCancellable {
        val job = scope.launch {
            getAppUpdate()
                .map { update ->
                    update?.let {
                        AppUpdateSnapshot(
                            version = it.version,
                            url = it.url,
                        )
                    }
                }
                .distinctUntilChanged()
                .collectOnMain { onChange(it) }
        }
        return KeyguardCancellable(job)
    }

    fun observeAppUpdateSettings(
        onChange: (AppUpdateSettingsSnapshot) -> Unit,
    ): KeyguardCancellable {
        val job = scope.launch {
            combine(
                getAppUpdateLevel(),
                getAppUpdateDelay(),
                getAppUpdateDelayVariants(),
            ) { level, delay, variants ->
                val levelOptions = AppUpdateLevel.entries.map { entry ->
                    SettingOptionSnapshot(
                        id = entry.key,
                        title = text(entry.title),
                        selected = entry == level,
                    )
                }
                val delayOptions = variants.map { duration ->
                    SettingOptionSnapshot(
                        id = duration.inWholeMilliseconds.toString(),
                        title = formatDelay(duration),
                        selected = duration == delay,
                    )
                }
                val snapshot = AppUpdateSettingsSnapshot(
                    levelOptions = levelOptions,
                    delayOptions = delayOptions,
                )
                variants to snapshot
            }.collectOnMain { (variants, snapshot) ->
                latestDelayVariants = variants
                onChange(snapshot)
            }
        }
        return KeyguardCancellable(job)
    }

    fun setLevel(optionId: String) {
        val level = AppUpdateLevel.entries.firstOrNull { it.key == optionId } ?: return
        putAppUpdateLevel(level).launchIn(scope)
    }

    fun setDelay(optionId: String) {
        val duration = latestDelayVariants
            .firstOrNull { it.inWholeMilliseconds.toString() == optionId }
            ?: return
        putAppUpdateDelay(duration).launchIn(scope)
    }
}
