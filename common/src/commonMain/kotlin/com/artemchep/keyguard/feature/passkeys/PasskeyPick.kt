package com.artemchep.keyguard.feature.passkeys

import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.PasskeyTargetCheck
import com.artemchep.keyguard.feature.confirmation.elevatedaccess.createElevatedAccessVerify
import com.artemchep.keyguard.feature.confirmation.elevatedaccess.runVerified
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Returns the action that hands the picked [credential] of the [cipher] to the [mode], or
 * `null` if the credential does not match the [mode]'s target. The action first confirms
 * access to an item that requires it. Every picker completes through here, so none can
 * skip the confirmation. A confirmation already [granted] on this screen is reused.
 */
suspend fun RememberStateFlowScope.passkeyPickAction(
    mode: AppMode.PickPasskey,
    cipher: DSecret,
    credential: DSecret.Login.Fido2Credentials,
    passkeyTargetCheck: PasskeyTargetCheck,
    granted: MutableStateFlow<Boolean>? = null,
): (() -> Unit)? {
    val matches = passkeyTargetCheck(credential, mode.target)
        .attempt()
        .bind()
        .isRight { it }
    if (!matches) {
        return null
    }
    return {
        createElevatedAccessVerify(required = cipher.reprompt, granted = granted)
            .runVerified { mode.onComplete(cipher, credential) }
    }
}
