package com.artemchep.keyguard.apple.onboarding

import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetOnboardingLastVisitInstant
import com.artemchep.keyguard.common.usecase.PutOnboardingLastVisitInstant
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Mirrors the Compose `OnboardingBanner` / `OnboardingScreen` pair: the banner is shown while the stored
 * last-visit instant is `null`, and visiting / dismissing it stamps the current instant so it never shows again.
 *
 * The feature content is static localized text, so Swift renders it from the shared string catalog; only the
 * "has onboarded" flag and the mark-as-done action cross here.
 */
internal class OnboardingController(
    private val ctx: CoreContext,
) {
    private val getInstant: GetOnboardingLastVisitInstant by lazy { ctx.koin.get() }
    private val putInstant: PutOnboardingLastVisitInstant by lazy { ctx.koin.get() }

    fun observeOnboarding(
        onChange: (Boolean) -> Unit,
    ): KeyguardCancellable {
        val job = ctx.scope.launch {
            getInstant()
                .map { it != null }
                .collect { hasOnboarded ->
                    onChange(hasOnboarded)
                }
        }
        return KeyguardCancellable(job)
    }

    /** Mirrors the Compose `OnboardingScreen` `LaunchedEffect`. */
    fun markOnboarded() {
        putInstant(Clock.System.now()).launchIn(ctx.scope)
    }
}
