package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.common.usecase.Fido2UnlockAvailability

import com.artemchep.keyguard.common.model.BiometricAuthException
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.ShowMessage
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.getString
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.service.vault.FingerprintReadRepository
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.common.usecase.DisableBiometric
import com.artemchep.keyguard.common.usecase.EnableBiometric
import com.artemchep.keyguard.common.usecase.GetBiometricTimeout
import com.artemchep.keyguard.common.usecase.GetBiometricTimeoutVariants
import com.artemchep.keyguard.common.usecase.GetClipboardAutoClear
import com.artemchep.keyguard.common.usecase.GetClipboardAutoClearVariants
import com.artemchep.keyguard.common.usecase.GetConcealFields
import com.artemchep.keyguard.common.usecase.GetGravatar
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterReboot
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterTimeout
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterTimeoutVariants
import com.artemchep.keyguard.common.usecase.GetVaultPersist
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.common.usecase.PutBiometricTimeout
import com.artemchep.keyguard.common.usecase.PutClipboardAutoClear
import com.artemchep.keyguard.common.usecase.PutConcealFields
import com.artemchep.keyguard.common.usecase.PutGravatar
import com.artemchep.keyguard.common.usecase.PutVaultLockAfterReboot
import com.artemchep.keyguard.common.usecase.PutVaultLockAfterTimeout
import com.artemchep.keyguard.common.usecase.PutVaultPersist
import com.artemchep.keyguard.common.usecase.PutWebsiteIcons
import com.artemchep.keyguard.common.usecase.YubiKeyUnlockAvailability
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.evaluateBiometrics
import com.artemchep.keyguard.apple.model.SettingOptionSnapshot
import com.artemchep.keyguard.platform.LeBiometricCipherApple
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.format
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The Security settings screen: vault persist, auto-lock timeout, lock-after-
 * reboot, clipboard auto-clear, conceal fields, website icons, Gravatar, and
 * Touch ID unlock. Thin bridge over the shared Get/Put use cases; enabling Touch
 * ID shows the system sheet via the shared [evaluateBiometrics]. Items the common
 * providers hide on Apple are intentionally not surfaced.
 */
internal class SecurityController(
    private val ctx: CoreContext,
) {
    private val getVaultPersist: GetVaultPersist by lazy { ctx.koin.get() }
    private val putVaultPersist: PutVaultPersist by lazy { ctx.koin.get() }
    private val getVaultLockAfterReboot: GetVaultLockAfterReboot by lazy { ctx.koin.get() }
    private val putVaultLockAfterReboot: PutVaultLockAfterReboot by lazy { ctx.koin.get() }
    private val getVaultLockAfterTimeout: GetVaultLockAfterTimeout by lazy { ctx.koin.get() }
    private val getVaultLockAfterTimeoutVariants: GetVaultLockAfterTimeoutVariants by lazy { ctx.koin.get() }
    private val putVaultLockAfterTimeout: PutVaultLockAfterTimeout by lazy { ctx.koin.get() }
    private val getClipboardAutoClear: GetClipboardAutoClear by lazy { ctx.koin.get() }
    private val getClipboardAutoClearVariants: GetClipboardAutoClearVariants by lazy { ctx.koin.get() }
    private val putClipboardAutoClear: PutClipboardAutoClear by lazy { ctx.koin.get() }
    private val getConcealFields: GetConcealFields by lazy { ctx.koin.get() }
    private val putConcealFields: PutConcealFields by lazy { ctx.koin.get() }
    private val getWebsiteIcons: GetWebsiteIcons by lazy { ctx.koin.get() }
    private val putWebsiteIcons: PutWebsiteIcons by lazy { ctx.koin.get() }
    private val getGravatar: GetGravatar by lazy { ctx.koin.get() }
    private val putGravatar: PutGravatar by lazy { ctx.koin.get() }
    private val biometricStatusUseCase: BiometricStatusUseCase by lazy { ctx.koin.get() }
    private val fingerprintReadRepository: FingerprintReadRepository by lazy { ctx.koin.get() }
    private val getBiometricTimeout: GetBiometricTimeout by lazy { ctx.koin.get() }
    private val getBiometricTimeoutVariants: GetBiometricTimeoutVariants by lazy { ctx.koin.get() }
    private val putBiometricTimeout: PutBiometricTimeout by lazy { ctx.koin.get() }
    private val enableBiometric: EnableBiometric by lazy { ctx.koin.get() }
    private val disableBiometric: DisableBiometric by lazy { ctx.koin.get() }
    private val yubiKeyUnlockAvailability: YubiKeyUnlockAvailability by lazy { ctx.koin.get() }

    private var latestLockTimeoutVariants: List<Duration> = emptyList()
    private var latestClipboardAutoClearVariants: List<Duration> = emptyList()
    private var latestBiometricTimeoutVariants: List<Duration> = emptyList()

    /** Combined Touch ID + YubiKey unlock state for [observeSecuritySettings]. */
    private data class BiometricSettings(
        val supported: Boolean,
        val enabled: Boolean,
        val timeout: Duration,
        val timeoutVariants: List<Duration>,
        val yubiKeySupported: Boolean,
        val yubiKeyEnabled: Boolean,
        val fido2Supported: Boolean,
        val fido2Enabled: Boolean,
    )

    fun observeSecuritySettings(
        onChange: (SecuritySettingsSnapshot) -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        val lockFlow = combine(
            getVaultLockAfterTimeout(),
            getVaultLockAfterTimeoutVariants(),
        ) { current, variants -> current to variants }
        val clipFlow = combine(
            getClipboardAutoClear(),
            getClipboardAutoClearVariants(),
        ) { current, variants -> current to variants }
        val visualsFlow = combine(
            getConcealFields(),
            getWebsiteIcons(),
            getGravatar(),
        ) { conceal, websiteIcons, gravatar -> Triple(conceal, websiteIcons, gravatar) }
        val biometricFlow = combine(
            biometricStatusUseCase(),
            fingerprintReadRepository.get(),
            getBiometricTimeout(),
            getBiometricTimeoutVariants(),
        ) { status, tokens, timeout, variants ->
            val enabled = tokens?.biometric != null
            BiometricSettings(
                supported = status is BiometricStatus.Available,
                enabled = enabled,
                timeout = timeout,
                timeoutVariants = variants.takeIf { enabled }.orEmpty(),
                yubiKeySupported = yubiKeyUnlockAvailability.isSupported(),
                yubiKeyEnabled = tokens?.yubiKey != null,
                fido2Supported = ctx.koin.get<Fido2UnlockAvailability>().isSupported(),
                fido2Enabled = tokens?.fido2 != null,
            )
        }
        val visualsAndBiometricFlow = combine(
            visualsFlow,
            biometricFlow,
        ) { visuals, biometric -> visuals to biometric }
        val job = ctx.scope.launch {
            combine(
                getVaultPersist(),
                getVaultLockAfterReboot(),
                lockFlow,
                clipFlow,
                visualsAndBiometricFlow,
            ) { persist, lockAfterReboot, lock, clip, (visuals, biometric) ->
                latestLockTimeoutVariants = lock.second
                latestClipboardAutoClearVariants = clip.second
                latestBiometricTimeoutVariants = biometric.timeoutVariants
                SecuritySettingsSnapshot(
                    loaded = true,
                    vaultPersist = persist,
                    lockAfterRebootVisible = persist,
                    lockAfterReboot = lockAfterReboot,
                    lockTimeoutTitle = lockAfterTimeoutTitle(lock.first, leContext),
                    lockTimeoutOptions = lock.second.map { duration ->
                        SettingOptionSnapshot(
                            id = duration.inWholeMilliseconds.toString(),
                            title = lockAfterTimeoutTitle(duration, leContext),
                            selected = duration == lock.first,
                        )
                    },
                    clipboardAutoClearTitle = clipboardAutoClearTitle(clip.first, leContext),
                    clipboardAutoClearOptions = clip.second.map { duration ->
                        SettingOptionSnapshot(
                            id = duration.inWholeMilliseconds.toString(),
                            title = clipboardAutoClearTitle(duration, leContext),
                            selected = duration == clip.first,
                        )
                    },
                    conceal = visuals.first,
                    websiteIcons = visuals.second,
                    gravatar = visuals.third,
                    biometricUnlockSupported = biometric.supported,
                    biometricUnlockEnabled = biometric.enabled,
                    biometricTimeoutTitle = biometricTimeoutTitle(biometric.timeout, leContext),
                    biometricTimeoutOptions = biometric.timeoutVariants.map { duration ->
                        SettingOptionSnapshot(
                            id = duration.inWholeMilliseconds.toString(),
                            title = biometricTimeoutTitle(duration, leContext),
                            selected = duration == biometric.timeout,
                        )
                    },
                    yubiKeyUnlockSupported = biometric.yubiKeySupported,
                    yubiKeyUnlockEnabled = biometric.yubiKeyEnabled,
                    fido2UnlockSupported = biometric.fido2Supported,
                    fido2UnlockEnabled = biometric.fido2Enabled,
                )
            }.collect { onChange(it) }
        }
        return KeyguardCancellable(job)
    }

    /**
     * Enables / disables biometric (Touch ID) unlock. Enabling shows the system
     * Touch ID sheet first, then persists the biometric-encrypted master key; a
     * cancelled or failed prompt leaves the setting off.
     */
    fun setBiometricUnlock(value: Boolean) {
        ctx.scope.launch(Dispatchers.Default) {
            try {
                if (value) {
                    val withBiometric = enableBiometric(null).bind()
                    val cipher = withBiometric.getCipher() as LeBiometricCipherApple
                    val exception = evaluateBiometrics(
                        reason = getString(Res.string.pref_item_biometric_unlock_confirm_title),
                    ) { context -> cipher.materialize(context) }
                    if (exception != null) throw exception
                    withBiometric.getCreateIo().bind()
                } else {
                    disableBiometric().bind()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (e is BiometricAuthException && e.code in setOf(
                        BiometricAuthException.ERROR_USER_CANCELED,
                        BiometricAuthException.ERROR_CANCELED,
                        BiometricAuthException.ERROR_NEGATIVE_BUTTON,
                    )) return@launch
                ctx.koin.get<ShowMessage>().copy(
                    ToastMessage(
                        type = ToastMessage.Type.ERROR,
                        title = getString(Res.string.unlock_biometric_auth_failed),
                        text = e.message,
                    ),
                )
            }
        }
    }

    private suspend fun lockAfterTimeoutTitle(duration: Duration, context: LeContext): String =
        when (duration) {
            Duration.ZERO -> textResource(Res.string.pref_item_lock_vault_after_delay_immediately_text, context)
            Duration.INFINITE -> textResource(Res.string.pref_item_lock_vault_after_delay_never_text, context)
            else -> duration.format(context)
        }

    private suspend fun clipboardAutoClearTitle(duration: Duration, context: LeContext): String =
        when (duration) {
            Duration.ZERO -> textResource(Res.string.pref_item_clipboard_auto_clear_immediately_text, context)
            Duration.INFINITE -> textResource(Res.string.pref_item_clipboard_auto_clear_never_text, context)
            else -> duration.format(context)
        }

    private suspend fun biometricTimeoutTitle(duration: Duration, context: LeContext): String =
        when (duration) {
            Duration.ZERO -> textResource(Res.string.pref_item_require_app_password_immediately_text, context)
            Duration.INFINITE -> textResource(Res.string.pref_item_require_app_password_never_text, context)
            else -> duration.format(context)
        }

    fun setVaultPersist(value: Boolean) {
        putVaultPersist(value).launchIn(ctx.scope)
    }

    fun setVaultLockAfterReboot(value: Boolean) {
        putVaultLockAfterReboot(value).launchIn(ctx.scope)
    }

    fun setVaultLockTimeout(optionId: String) {
        val duration = latestLockTimeoutVariants
            .firstOrNull { it.inWholeMilliseconds.toString() == optionId }
            ?: return
        putVaultLockAfterTimeout(duration).launchIn(ctx.scope)
    }

    fun setBiometricTimeout(optionId: String) {
        val duration = latestBiometricTimeoutVariants
            .firstOrNull { it.inWholeMilliseconds.toString() == optionId }
            ?: return
        putBiometricTimeout(duration).launchIn(ctx.scope)
    }

    fun setClipboardAutoClear(optionId: String) {
        val duration = latestClipboardAutoClearVariants
            .firstOrNull { it.inWholeMilliseconds.toString() == optionId }
            ?: return
        putClipboardAutoClear(duration).launchIn(ctx.scope)
    }

    fun setConcealFields(value: Boolean) {
        putConcealFields(value).launchIn(ctx.scope)
    }

    fun setWebsiteIcons(value: Boolean) {
        putWebsiteIcons(value).launchIn(ctx.scope)
    }

    fun setGravatar(value: Boolean) {
        putGravatar(value).launchIn(ctx.scope)
    }
}
