package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.feature.auth.bitwarden.LoginState
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.TwoFactorState
import com.artemchep.keyguard.feature.keyguard.setup.SetupState
import com.artemchep.keyguard.feature.keyguard.unlock.UnlockState
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.apple.model.ActionKeyAllocator
import com.artemchep.keyguard.apple.model.KeyguardCipher
import com.artemchep.keyguard.apple.model.TextFieldSnapshot
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.flow.map
import platform.Foundation.create

/**
 * A flat, Swift-friendly projection of [UnlockState] for the SwiftUI unlock
 * screen. Mirrors the style of [KeyguardCipher].
 */
data class UnlockSnapshot(
    val password: String,
    val passwordError: String?,
    val isLoading: Boolean,
    val canUnlock: Boolean,
    val hasBiometric: Boolean,
    /** Device has a YubiKey unlock factor enrolled (offers the YubiKey button). */
    val hasYubiKey: Boolean,
    val hasFido2: Boolean = false,
    /** Why the vault locked (e.g. "Locked manually"), shown above the form. */
    val lockReason: String?,
    /**
     * Escape-hatch actions offered on the unlock screen — chiefly "Erase data"
     * for a forgotten password. [UnlockActionSnapshot.id] routes back to
     * [KeyguardCore.invokeUnlockAction]. All are treated as destructive by the
     * SwiftUI screen (it confirms before invoking).
     */
    val actions: List<UnlockActionSnapshot>,
) {
    companion object {
        val empty = UnlockSnapshot(
            password = "",
            passwordError = null,
            isLoading = false,
            canUnlock = false,
            hasBiometric = false,
            hasYubiKey = false,
            lockReason = null,
            actions = emptyList(),
        )
    }
}
/** An unlock-screen escape-hatch action (e.g. "Erase data"). */
data class UnlockActionSnapshot(
    val id: String,
    val title: String,
)

internal fun UnlockState?.toUnlockSnapshot(
    actions: List<UnlockActionSnapshot> = emptyList(),
): UnlockSnapshot {
    this ?: return UnlockSnapshot.empty
    return UnlockSnapshot(
        password = password.text,
        passwordError = password.error,
        isLoading = isLoading,
        canUnlock = unlockVaultByMasterPassword != null,
        hasBiometric = biometric != null,
        hasYubiKey = yubiKey != null,
        hasFido2 = fido2 != null,
        lockReason = lockReason,
        actions = actions,
    )
}

/**
 * Projects the unlock screen's [UnlockState.actions] into flat snapshots,
 * capturing each [FlatItemAction.onClick] into [handlers] keyed by the assigned
 * id (mirrors the login screen's action handler map). Non-action context items
 * (dividers, etc.) are skipped.
 */
internal suspend fun UnlockState?.toUnlockActionSnapshots(
    leContext: LeContext,
    handlers: MutableMap<String, () -> Unit>,
): List<UnlockActionSnapshot> {
    this ?: return emptyList()
    val keys = ActionKeyAllocator("unlock")
    val acc = ArrayList<UnlockActionSnapshot>()
    for (ci in actions) {
        if (ci !is FlatItemAction) continue
        val onClick = ci.onClick ?: continue
        val title = textResource(ci.title, leContext)
        val id = keys.keyFor(ci, title)
        handlers[id] = onClick
        acc += UnlockActionSnapshot(
            id = id,
            title = title,
        )
    }
    return acc
}

/**
 * A flat, Swift-friendly projection of [SetupState] for the SwiftUI create-vault
 * screen. The sibling of [UnlockSnapshot]: the shared `setupStateProducer` owns
 * password validation, the crash-reporting opt-in, biometric enrollment and the
 * "can create" gate, so the Swift screen renders this and hands edits back
 * through [KeyguardCore.setSetupPassword] / `setSetupCrashlytics` /
 * `setSetupBiometric` / `submitSetup`.
 *
 *  - [passwordError]: inline validation error (e.g. min-length), or null,
 *  - [crashlyticsEnabled]: state of the "send crash reports" checkbox,
 *  - [hasBiometric]: device supports Touch ID enrollment (toggle visible),
 *  - [biometricEnabled]: the biometric checkbox is ticked,
 *  - [canCreate]: the producer's `onCreateVault` gate is satisfied.
 */
data class SetupSnapshot(
    val passwordError: String?,
    val crashlyticsEnabled: Boolean,
    val hasBiometric: Boolean,
    val biometricEnabled: Boolean,
    val isLoading: Boolean,
    val canCreate: Boolean,
) {
    companion object {
        val empty = SetupSnapshot(
            passwordError = null,
            crashlyticsEnabled = false,
            hasBiometric = false,
            biometricEnabled = false,
            isLoading = false,
            canCreate = false,
        )
    }
}

internal fun SetupState?.toSetupSnapshot(): SetupSnapshot {
    this ?: return SetupSnapshot.empty
    return SetupSnapshot(
        passwordError = password.error,
        crashlyticsEnabled = crashlytics.checked,
        hasBiometric = biometric != null,
        biometricEnabled = biometric?.checked == true,
        isLoading = isLoading,
        canCreate = onCreateVault != null,
    )
}

/**
 * A flat projection of a shared [ToastMessage] for SwiftUI. The shared producers
 * route their runtime errors (a wrong master password on unlock, a failed create
 * IO, sync failures, …) through the global message bus rather than into a screen
 * state, so [KeyguardCore.observeMessages] forwards them as these. [type] is the
 * [ToastMessage.Type] name ("INFO" / "ERROR" / "SUCCESS"), or null.
 */
data class MessageSnapshot(
    val id: String,
    val type: String?,
    val title: String,
    val text: String?,
    val durationMillis: Long,
) {
    val isError: Boolean get() = type == ToastMessage.Type.ERROR.name
}

// Mirrors the durations the Compose `ToastComposable` applies per type.
private const val MSG_ERROR_DURATION_MS = 4500L
private const val MSG_NORMAL_DURATION_MS = 2500L

internal fun ToastMessage.toMessageSnapshot(): MessageSnapshot = MessageSnapshot(
    id = id,
    type = type?.name,
    title = title,
    text = text,
    durationMillis = when (type) {
        ToastMessage.Type.ERROR -> MSG_ERROR_DURATION_MS
        else -> MSG_NORMAL_DURATION_MS
    },
)

/** A selectable server region (US / EU / Custom) for the segmented selector. */
data class LoginRegionSnapshot(
    val key: String,
    val title: String,
    val checked: Boolean,
)

/** A menu / button action; [id] routes back to [KeyguardCore.invokeLoginAction]. */
data class LoginActionSnapshot(
    val id: String,
    val title: String,
)

enum class LoginItemKind {
    URL,
    HEADER,
    SECTION,
    LABEL,
    ADD,
}

/**
 * One row of the dynamic self-hosted custom-environment editor. The populated
 * fields depend on [kind]:
 * - [LoginItemKind.URL]: [field] (the URL text field, carries its label).
 * - [LoginItemKind.HEADER]: [keyField] + [field] (header name + value) and [actions].
 * - [LoginItemKind.SECTION] / [LoginItemKind.LABEL]: [text].
 * - [LoginItemKind.ADD]: [text] (button title) + [actions].
 */
data class LoginItemSnapshot(
    val id: String,
    val kind: LoginItemKind,
    val field: TextFieldSnapshot?,
    val keyField: TextFieldSnapshot?,
    val label: String?,
    val text: String?,
    val actions: List<LoginActionSnapshot>,
)

/**
 * A flat, Swift-friendly projection of [LoginState] for the SwiftUI Bitwarden
 * login screen. Mirrors the full Compose form: region selector, optional CAPTCHA
 * client secret, the dynamic custom-environment editor, register + login actions.
 */
data class LoginSnapshot(
    val email: TextFieldSnapshot,
    val password: TextFieldSnapshot,
    val clientSecret: TextFieldSnapshot?,
    val regions: List<LoginRegionSnapshot>,
    val showCustomEnv: Boolean,
    val items: List<LoginItemSnapshot>,
    val isLoading: Boolean,
    val canLogin: Boolean,
    val canRegister: Boolean,
) {
    companion object {
        val empty = LoginSnapshot(
            email = TextFieldSnapshot.empty("email"),
            password = TextFieldSnapshot.empty("password"),
            clientSecret = null,
            regions = emptyList(),
            showCustomEnv = false,
            items = emptyList(),
            isLoading = false,
            canLogin = false,
            canRegister = false,
        )
    }
}

/** A selectable 2FA provider for the segmented selector; [key] routes back to
 * [KeyguardCore.selectTwofaProvider]. */
data class TwofaProviderSnapshot(
    val key: String,
    val title: String,
    val checked: Boolean,
)

/**
 * Which 2FA UI to render. The code-entry kinds (authenticator / email /
 * email-new-device / yubikey) drive a verification-code form; [FALLBACK] covers
 * providers that need an embedded WebView or browser callback not supported on
 * macOS yet (Duo / FIDO2-WebAuthn / otherwise unsupported) and offers a
 * "finish in the web vault" escape hatch.
 */
enum class TwofaKind {
    SKELETON,
    AUTHENTICATOR,
    EMAIL,
    EMAIL_NEW_DEVICE,
    YUBIKEY,
    FALLBACK,
}

/**
 * A flat, Swift-friendly projection of the shared `TwoFactorState` for the
 * SwiftUI 2FA screen. [code] (id "twofa.code") backs the verification-code field
 * for the authenticator / email kinds; the yubikey kind keeps its OTP Swift-side
 * and submits via [KeyguardCore.submitTwofaYubiKey].
 */
data class TwofaSnapshot(
    val providers: List<TwofaProviderSnapshot>,
    val kind: TwofaKind,
    val code: TextFieldSnapshot?,
    val emailNote: String?,
    val canResend: Boolean,
    val rememberMe: Boolean,
    val rememberMeEnabled: Boolean,
    val fallbackTitle: String?,
    val webVaultUrl: String?,
    val primaryActionText: String?,
    val canSubmit: Boolean,
    val isLoading: Boolean,
) {
    companion object {
        val empty = TwofaSnapshot(
            providers = emptyList(),
            kind = TwofaKind.SKELETON,
            code = null,
            emailNote = null,
            canResend = false,
            rememberMe = false,
            rememberMeEnabled = false,
            fallbackTitle = null,
            webVaultUrl = null,
            primaryActionText = null,
            canSubmit = false,
            isLoading = false,
        )
    }
}
