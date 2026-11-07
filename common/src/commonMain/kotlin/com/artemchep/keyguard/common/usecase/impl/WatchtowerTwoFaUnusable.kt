package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DWatchtowerAlertType
import com.artemchep.keyguard.common.model.ignores
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Bump when the one-time password parser accepts or rejects
 * different values, so the stored results are re-evaluated.
 */
private const val TWO_FA_UNUSABLE_VERSION = "1"

/**
 * Flags the logins whose one-time password Keyguard can not
 * generate codes from.
 */
class WatchtowerTwoFaUnusable : WatchtowerClientTyped {
    override val type: Long
        get() = DWatchtowerAlertType.TWO_FA_UNUSABLE.value

    override fun version(): Flow<String> = flowOf(TWO_FA_UNUSABLE_VERSION)

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> = ciphers.map { cipher ->
        val value = if (cipher.ignores(DWatchtowerAlertType.TWO_FA_UNUSABLE)) {
            null
        } else {
            cipher.login?.totpUnsupported
        }
        WatchtowerClientResult(
            // The value is a secret, so only store whether
            // there is an alert.
            value = value?.let { "unsupported" },
            threat = value != null,
            cipher = cipher,
        )
    }
}
