package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.feature.keyguard.unlock.UnlockState
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.apple.model.ActionKeyAllocator
import com.artemchep.keyguard.apple.model.TextFieldSnapshot
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.ui.FlatItemAction

data class UnlockOptionsSnapshot(
    val isLoading: Boolean,
    val hasBiometric: Boolean,
    val hasYubiKey: Boolean,
    val hasFido2: Boolean = false,
    /** Why the vault locked (e.g. "Locked manually"). */
    val lockReason: String?,
    /**
     * Escape-hatch actions such as "Erase data"; [UnlockActionSnapshot.id] routes back to
     * [KeyguardCore.invokeUnlockAction]. The SwiftUI screen confirms each one as destructive before invoking it.
     */
    val actions: List<UnlockActionSnapshot>,
) {
    companion object {
        val empty = UnlockOptionsSnapshot(
            isLoading = false,
            hasBiometric = false,
            hasYubiKey = false,
            lockReason = null,
            actions = emptyList(),
        )
    }
}
data class UnlockActionSnapshot(
    val id: String,
    val title: String,
)

internal fun UnlockState?.toUnlockOptionsSnapshot(
    actions: List<UnlockActionSnapshot> = emptyList(),
): UnlockOptionsSnapshot {
    this ?: return UnlockOptionsSnapshot.empty
    return UnlockOptionsSnapshot(
        isLoading = isLoading,
        hasBiometric = biometric != null,
        hasYubiKey = yubiKey != null,
        hasFido2 = fido2 != null,
        lockReason = lockReason,
        actions = actions,
    )
}

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
 * Shared producers report runtime errors (a wrong master password, a failed save, sync failures) through the
 * global message bus rather than screen state; [KeyguardCore.observeMessages] forwards them as these.
 * [type] is the [ToastMessage.Type] name ("INFO" / "ERROR" / "SUCCESS"), or null.
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

data class LoginRegionSnapshot(
    val key: String,
    val title: String,
    val checked: Boolean,
)

/** [id] routes back to [BitwardenLoginSession.invokeLoginAction]. */
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
 * - [LoginItemKind.URL]: [field] (the URL text field) + [label].
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

/** The action that runs [BitwardenLoginSession.discoverLoginServer]. */
data class LoginServerDiscoverySnapshot(
    val isLoading: Boolean,
    val enabled: Boolean,
)

/**
 * [clientSecret] is the CAPTCHA client secret field, or null while the server doesn't ask for one.
 * [serverDiscovery] offers to look up the server of the email domain, or null while there is no domain.
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
    val serverDiscovery: LoginServerDiscoverySnapshot?,
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
            serverDiscovery = null,
        )
    }
}

/** [key] routes back to [BitwardenLoginSession.selectTwofaProvider]. */
data class TwofaProviderSnapshot(
    val key: String,
    val title: String,
    val checked: Boolean,
)

/**
 * [FALLBACK] covers providers that need a WebView or browser callback the Apple apps don't support (Duo,
 * FIDO2 WebAuthn, unknown providers) and offers to finish in the web vault.
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
 * [code] (id "twofa.code") backs the verification-code field of the authenticator / email kinds; the yubikey
 * kind keeps its OTP Swift-side and submits via [BitwardenLoginSession.submitTwofaYubiKey].
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
