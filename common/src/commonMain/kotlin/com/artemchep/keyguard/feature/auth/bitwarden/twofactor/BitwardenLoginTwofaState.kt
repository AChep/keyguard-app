package com.artemchep.keyguard.feature.auth.bitwarden.twofactor

import androidx.compose.runtime.Immutable
import arrow.core.Either
import arrow.optics.optics
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.yubikey.OnYubiKeyListener
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderType

@Immutable
@optics
sealed class BitwardenLoginTwofaState {
    companion object

    interface HasPrimaryAction {
        val primaryAction: PrimaryAction?
    }

    @Immutable
    @optics
    data class PrimaryAction(
        val text: String = "",
        val icon: Icon = Icon.LOADING,
        val onClick: (() -> Unit)? = null,
    ) {
        companion object;

        enum class Icon {
            LOGIN,
            LAUNCH,
            LOADING,
        }
    }

    data object Skeleton : BitwardenLoginTwofaState()

    @Immutable
    @optics
    data class Unsupported(
        val providerType: TwoFactorProviderType?,
        val onLaunchWebVault: (() -> Unit)?,
        override val primaryAction: PrimaryAction? = null,
    ) : BitwardenLoginTwofaState(), HasPrimaryAction {
        companion object
    }

    @Immutable
    @optics
    data class Authenticator(
        val code: TextFieldModel2,
        val rememberMe: RememberMe = RememberMe(),
        override val primaryAction: PrimaryAction? = null,
    ) : BitwardenLoginTwofaState(), HasPrimaryAction {
        companion object

        @Immutable
        data class RememberMe(
            val checked: Boolean = false,
            val onChange: ((Boolean) -> Unit)? = null,
        ) {
            companion object
        }
    }

    @Immutable
    @optics
    data class YubiKey(
        val onComplete: OnYubiKeyListener?,
        val rememberMe: RememberMe = RememberMe(),
        override val primaryAction: PrimaryAction? = null,
    ) : BitwardenLoginTwofaState(), HasPrimaryAction {
        companion object

        @Immutable
        data class RememberMe(
            val checked: Boolean = false,
            val onChange: ((Boolean) -> Unit)? = null,
        ) {
            companion object
        }
    }

    @Immutable
    @optics
    data class Email(
        val email: String? = null,
        val emailResend: (() -> Unit)? = null,
        val code: TextFieldModel2,
        val rememberMe: RememberMe = RememberMe(),
        override val primaryAction: PrimaryAction? = null,
    ) : BitwardenLoginTwofaState(), HasPrimaryAction {
        companion object

        @Immutable
        @optics
        data class RememberMe(
            val checked: Boolean = false,
            val onChange: ((Boolean) -> Unit)? = null,
        ) {
            companion object
        }
    }

    @Immutable
    @optics
    data class EmailNewDevice(
        val email: String? = null,
        val emailResend: (() -> Unit)? = null,
        val code: TextFieldModel2,
        override val primaryAction: PrimaryAction? = null,
    ) : BitwardenLoginTwofaState(), HasPrimaryAction {
        companion object
    }

    @Immutable
    @optics
    data class Fido2WebAuthn(
        val authUrl: String,
        val callbackUrls: Set<String>,
        val error: String? = null,
        val rememberMe: RememberMe = RememberMe(),
        val onBrowser: (() -> Unit)?,
        val onComplete: ((Either<Throwable, String>) -> Unit)?,
    ) : BitwardenLoginTwofaState() {
        companion object

        @Immutable
        @optics
        data class RememberMe(
            val checked: Boolean = false,
            val onChange: ((Boolean) -> Unit)? = null,
        ) {
            companion object
        }
    }

    @Immutable
    @optics
    data class Duo(
        val authUrl: String,
        val error: String? = null,
        val rememberMe: RememberMe = RememberMe(),
        val onComplete: ((Either<Throwable, String>) -> Unit)?,
    ) : BitwardenLoginTwofaState() {
        companion object

        @Immutable
        @optics
        data class RememberMe(
            val checked: Boolean = false,
            val onChange: ((Boolean) -> Unit)? = null,
        ) {
            companion object
        }
    }
}