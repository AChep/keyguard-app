package com.artemchep.keyguard.feature.yubikey

import androidx.compose.runtime.Composable
import arrow.core.Either
import kotlinx.coroutines.flow.Flow

internal data class YubiKeyProvisionProbePrompt(
    val slot: Int,
    val onComplete: (Either<Throwable, YubiKeyProvisionProbeResult>) -> Unit,
)
internal data class YubiKeyProvisionProbeResult(val slot: Int, val isConfigured: Boolean)
internal class YubiKeyProvisionPrompt(
    val slot: Int,
    val challenge: ByteArray,
    val secret: ByteArray,
    val overwrite: Boolean,
    val onComplete: (Either<Throwable, ByteArray>) -> Unit,
)

@Composable
internal expect fun YubiKeyProvisionProbeEffect(flow: Flow<YubiKeyProvisionProbePrompt>)

@Composable
internal expect fun YubiKeyProvisionEffect(flow: Flow<YubiKeyProvisionPrompt>)
