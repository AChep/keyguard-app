package com.artemchep.keyguard.apple.auth

import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.res.unlock_biometric_key_unavailable
import com.artemchep.keyguard.common.model.BiometricAuthException
import com.artemchep.keyguard.common.model.BiometricAuthPrompt
import com.artemchep.keyguard.common.model.BiometricAuthPromptSimple
import com.artemchep.keyguard.common.model.YubiKeyAuthPrompt
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.evaluateBiometrics
import com.artemchep.keyguard.platform.LeBiometricCipherApple
import com.artemchep.keyguard.feature.yubikey.asYubiKeyAppException
import com.artemchep.keyguard.util.yubikey.YubiKeyClient
import com.artemchep.keyguard.util.yubikey.YubiKeyOperation
import com.artemchep.keyguard.util.yubikey.YubiKeyResult
import kotlinx.coroutines.CancellationException

/** Hosts application prompts; all YubiKey device operations live in the utility. */
internal class AuthPromptHost(
    private val ctx: CoreContext,
    private val yubiKeyClient: YubiKeyClient,
) {
    suspend fun handleBiometricPrompt(
        prompt: BiometricAuthPrompt,
        reason: String,
    ) {
        val result = try {
            val cipher = prompt.cipher as LeBiometricCipherApple
            val exception = evaluateBiometrics(reason) { context -> cipher.materialize(context) }
            exception?.left() ?: cipher.right()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: BiometricAuthException) {
            e.left()
        } catch (e: Throwable) {
            BiometricAuthException(
                BiometricAuthException.ERROR_UNKNOWN,
                org.jetbrains.compose.resources.getString(
                    com.artemchep.keyguard.res.Res.string.unlock_biometric_key_unavailable,
                ),
            ).also { it.addSuppressed(e) }.left()
        }
        ctx.publishOnMain { prompt.onComplete(result) }
    }

    suspend fun handleBiometricPromptSimple(
        prompt: BiometricAuthPromptSimple,
        reason: String,
    ) {
        val result = evaluateBiometrics(reason)?.left() ?: Unit.right()
        ctx.publishOnMain { prompt.onComplete(result) }
    }

    @Suppress("TooGenericExceptionCaught") // Complete the application prompt even when a bridge fails unexpectedly.
    suspend fun handleYubiKeyPrompt(prompt: YubiKeyAuthPrompt) {
        val result = try {
            runYubiKeyChallengeResponse(prompt.slot, prompt.challenge).right()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.asYubiKeyAppException(prompt.slot).left()
        }
        ctx.publishOnMain { prompt.onComplete(result) }
    }

    suspend fun runYubiKeyChallengeResponse(slot: Int, challenge: ByteArray): ByteArray =
        (execute(YubiKeyOperation.ChallengeResponse(slot, challenge)) as YubiKeyResult.Response).bytes

    suspend fun runYubiKeyProvision(
        slot: Int,
        secret: ByteArray,
        challenge: ByteArray,
        overwrite: Boolean,
    ): ByteArray = (execute(
        YubiKeyOperation.Provision(slot, challenge, secret, overwrite = overwrite),
    ) as YubiKeyResult.Response).bytes

    suspend fun inspectYubiKeySlot(slot: Int): Boolean =
        (execute(YubiKeyOperation.Inspect(slot)) as YubiKeyResult.SlotStatus).configured

    @Suppress("TooGenericExceptionCaught") // Preserve readable application errors at the device boundary.
    private suspend fun execute(operation: YubiKeyOperation): YubiKeyResult = try {
        yubiKeyClient.execute(operation)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw error.asYubiKeyAppException(operation.slot, provisioning = operation is YubiKeyOperation.Provision)
    }
}
