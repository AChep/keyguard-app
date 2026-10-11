package com.artemchep.keyguard.ui.totp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.usecase.GetTotpCodeWithOffset
import org.koin.compose.koinInject

/**
 * Collects the [totpCodeFlow] while the screen is at least
 * started, so the countdown does not tick in the background.
 */
@Composable
fun produceTotpCode(
    totpToken: TotpToken,
    offset: Int = 0,
): State<TotpCodeState> {
    val getTotpCode = koinInject<GetTotpCodeWithOffset>()
    return remember(getTotpCode, totpToken, offset) {
        totpCodeFlow(
            getTotpCode = getTotpCode,
            totpToken = totpToken,
            offset = offset,
        )
    }.collectAsStateWithLifecycle(initialValue = TotpCodeState.Loading)
}
