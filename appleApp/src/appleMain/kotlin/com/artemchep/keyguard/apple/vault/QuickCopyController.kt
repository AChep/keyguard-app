package com.artemchep.keyguard.apple.vault

import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.GeneratorContext
import com.artemchep.keyguard.common.model.GetPasswordResult
import com.artemchep.keyguard.common.model.PasswordGeneratorConfig
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetPassword
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.feature.confirmation.elevatedaccess.ElevatedAccessResult
import com.artemchep.keyguard.feature.confirmation.elevatedaccess.ElevatedAccessRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import org.koin.core.scope.Scope

/**
 * One-shot clipboard helpers — quick copy of a single cipher field, and
 * quick-generate a password — fired from the menu-bar popover and the Recents
 * list. The searchable item lists themselves are served by the
 * `VaultListSession` / delta pipelines; this holds only the copy / generate
 * actions, which are independent of any list.
 */
internal class QuickCopyController(
    private val ctx: CoreContext,
) {
    var navigationInterceptorProvider: ((Scope) -> ((NavigationIntent) -> Boolean))? = null

    private val clipboardService: ClipboardService by lazy { ctx.koin.get() }
    private val getPassword: GetPassword by lazy { ctx.koin.get() }

    /**
     * One-shot copy of a single field of a vault item to the clipboard, used by
     * the menu-bar popover. [field] is "username", "password" or "otp". Resolves
     * the cipher from the unlocked session, reuses the shared [ClipboardService]
     * (passwords are copied concealed), and computes the live OTP via the shared
     * [GetTotpCode]. No-op while locked or when the field is absent.
     *
     * Named "quick…" rather than "copy…": Kotlin/Native drops methods whose name
     * is in the Objective-C `copy` family from the Swift-visible API.
     */
    suspend fun quickCopyCipherField(secretId: String, accountId: String, field: String) {
        val state = ctx.currentState() as? VaultState.Main ?: return
        val getCiphers = state.sessionKoin.get<GetCiphers>()
        val secret = getCiphers().first()
            .firstOrNull { it.id == secretId && it.accountId == accountId }
            ?: return
        // Match cipher detail: public username and current OTP do not require
        // elevated access, but a protected password must never bypass it through
        // Recents, quick search, or the menu-bar popover.
        if (field == "password" && secret.reprompt && secret.login?.password != null) {
            val interceptor = navigationInterceptorProvider?.invoke(state.sessionKoin) ?: return
            var consumed = false
            val route = registerRouteResultReceiver(ElevatedAccessRoute()) { result ->
                if (!consumed) {
                    consumed = true
                    if (result is ElevatedAccessResult.Allow) {
                        ctx.scope.launch {
                            // Re-resolve after authentication: the vault could have
                            // locked, or the item could have changed while prompting.
                            if ((ctx.currentState() as? VaultState.Main)?.session !== state.session) return@launch
                            val current = getCiphers().first()
                                .firstOrNull { it.id == secretId && it.accountId == accountId }
                            if ((ctx.currentState() as? VaultState.Main)?.session !== state.session) return@launch
                            current?.login?.password?.let {
                                clipboardService.setPrimaryClip(it, concealed = true)
                            }
                        }
                    }
                }
            }
            ctx.publishOnMain { interceptor(NavigationIntent.NavigateToRoute(route)) }
            return
        }
        when (field) {
            "username" -> secret.login?.username
                ?.let { clipboardService.setPrimaryClip(it, concealed = false) }

            "password" -> secret.login?.password
                ?.let { clipboardService.setPrimaryClip(it, concealed = true) }

            "otp" -> {
                val token = secret.login?.totp?.token ?: return
                val getTotpCode = state.sessionKoin.get<GetTotpCode>()
                val code = getTotpCode(token).first().fold(ifLeft = { null }, ifRight = { it.code })
                    ?: return
                clipboardService.setPrimaryClip(code, concealed = false)
            }
        }
    }

    /**
     * One-shot "quick generate": generates a strong 16-character password using
     * the shared [GetPassword] use case and copies it to the clipboard. The full
     * generator screen remains the place to customize options.
     */
    suspend fun generateAndCopyPassword() {
        val config = PasswordGeneratorConfig.Password(
            length = 16,
            uppercaseChars = ('A'..'Z').toList(),
            lowercaseChars = ('a'..'z').toList(),
            numberChars = ('0'..'9').toList(),
            symbolChars = "!@#\$%^&*".toList(),
            uppercaseMin = 1L,
            lowercaseMin = 1L,
            numbersMin = 1L,
            symbolsMin = 1L,
        )
        val result = getPassword(GeneratorContext(host = null), config).bind()
        val value = (result as? GetPasswordResult.Value)?.value ?: return
        clipboardService.setPrimaryClip(value, concealed = false)
    }
}
