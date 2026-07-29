package com.artemchep.keyguard.feature.auth.userverification

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.keyguard.unlock.UnlockState

/**
 * The state of an in-place "prove you are present" gate: the vault is already
 * unlocked, but the action behind the gate needs the user to authenticate again.
 *
 * The dialog-shaped flavour of the same check is
 * [com.artemchep.keyguard.feature.confirmation.elevatedaccess.ElevatedAccessState].
 * This one reports through a plain callback instead of a route result, so there is
 * no dismissal that reports nothing and therefore no way to strand the caller.
 */
@Immutable
data class UserVerificationState(
    val content: Loadable<Content> = Loadable.Loading,
) {
    @Immutable
    data class Content(
        val sideEffects: UnlockState.SideEffects,
        val password: TextFieldModel,
        val biometric: Biometric? = null,
        val yubiKey: YubiKey? = null,
        val isLoading: Boolean = false,
        val onVerify: (() -> Unit)? = null,
    )

    // No `@optics` on these two, unlike their `UnlockState`/`ElevatedAccessState`
    // siblings. Arrow's processor is registered for `kspCommonMainMetadata` only
    // (common/build.gradle.kts), so while this lived in androidMain the annotation
    // generated nothing and nothing came to depend on the lenses. Moving it here
    // would have started generating them for no caller.
    @Immutable
    data class Biometric(
        val onClick: (() -> Unit)? = null,
    )

    @Immutable
    data class YubiKey(
        val onClick: (() -> Unit)? = null,
    )
}
