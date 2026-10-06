package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetDebugPremium
import com.artemchep.keyguard.common.usecase.PutDebugPremium
import com.artemchep.keyguard.platform.util.isRelease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Uses the same persisted Premium override as the Compose Development settings. */
internal class DebugSettingsController(
    private val getDebugPremium: GetDebugPremium,
    private val putDebugPremium: PutDebugPremium,
    private val scope: CoroutineScope,
    private val release: Boolean = isRelease,
) {
    fun observe(
        onChange: (DebugSettingsSnapshot) -> Unit,
    ): KeyguardCancellable {
        val job = scope.launch {
            if (release) {
                onChange(DebugSettingsSnapshot(loaded = true))
                return@launch
            }
            getDebugPremium().collect { enabled ->
                onChange(
                    DebugSettingsSnapshot(
                        loaded = true,
                        premiumOverrideAvailable = true,
                        premiumOverrideEnabled = enabled,
                    ),
                )
            }
        }
        return KeyguardCancellable(job)
    }

    fun setPremiumOverride(enabled: Boolean) {
        if (release) return
        putDebugPremium(enabled).launchIn(scope)
    }
}
