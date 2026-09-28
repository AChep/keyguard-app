package com.artemchep.keyguard.apple.onboarding

import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetOnboardingLastVisitInstant
import com.artemchep.keyguard.common.usecase.PutOnboardingLastVisitInstant
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * First-run onboarding. Thin bridge over the shared Get/PutOnboardingLastVisitInstant
 * use cases (backed by the settings repository), mirroring the Compose
 * `OnboardingBanner` / `OnboardingScreen` pair: the banner is shown while the user
 * has never visited the onboarding (the stored instant is `null`), and visiting /
 * dismissing it stamps the current instant so it never shows again.
 *
 * The feature content itself (the premium / search / watchtower / misc sections) is
 * static localized text, so it is rendered natively in Swift from the shared string
 * catalog — only the "has onboarded" flag and the mark-as-done action cross here.
 */
internal class OnboardingController(
    private val ctx: CoreContext,
) {
    private val getInstant: GetOnboardingLastVisitInstant by lazy { ctx.koin.get() }
    private val putInstant: PutOnboardingLastVisitInstant by lazy { ctx.koin.get() }

    /**
     * Observes whether the user has completed (or dismissed) the first-run
     * onboarding. Emits `true` once the last-visit instant has been stamped, `false`
     * while it is still `null` — the inverse of the Compose banner's visibility.
     */
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

    /**
     * Stamps the onboarding last-visit instant, mirroring the Compose
     * `OnboardingScreen` `LaunchedEffect`. After this the banner stops showing.
     */
    fun markOnboarded() {
        putInstant(Clock.System.now()).launchIn(ctx.scope)
    }
}
