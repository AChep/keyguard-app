package com.artemchep.keyguard.feature.auth.keyguard

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.usecase.ConfirmAccessByPasswordUseCase
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.awaitConfirmationDialog
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.provider.keyguard.KeyguardPasswordPrompt
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_authority_password_text
import com.artemchep.keyguard.res.app_link_authority_password_title
import com.artemchep.keyguard.res.app_password
import com.artemchep.keyguard.res.error_incorrect_password

/**
 * Asks for the master password, which adding or removing a linked device needs: only it
 * opens the authority key of this device, see
 * [com.artemchep.keyguard.provider.keyguard.KeyguardAuthorityKeys]. Biometrics and other
 * unlock factors do not. Asks again after a wrong password; returns `null` if the user
 * cancels.
 */
suspend fun RememberStateFlowScope.awaitKeyguardAuthorityPassword(
    confirmationRouteFactory: ConfirmationRouteFactory,
    confirmAccessByPassword: ConfirmAccessByPasswordUseCase,
): String? {
    var attempt = 0
    while (true) {
        val password = awaitConfirmationDialog(
            confirmationRouteFactory = confirmationRouteFactory,
            item = ConfirmationRoute.Args.Item.StringItem(
                key = "keyguard_authority_password:$attempt",
                title = translate(Res.string.app_password),
                hint = translate(Res.string.app_password),
                type = ConfirmationRoute.Args.Item.StringItem.Type.Password,
                canBeEmpty = false,
                ephemeral = true,
            ),
            title = translate(Res.string.app_link_authority_password_title),
            message = translate(
                if (attempt > 0) Res.string.error_incorrect_password else Res.string.app_link_authority_password_text,
            ),
        ) ?: return null
        if (confirmAccessByPassword(password).bind()) return password
        attempt++
    }
}

/** Returns the [KeyguardPasswordPrompt] of the screen, see [awaitKeyguardAuthorityPassword]. */
fun RememberStateFlowScope.keyguardAuthorityPasswordPrompt(
    confirmationRouteFactory: ConfirmationRouteFactory,
    confirmAccessByPassword: ConfirmAccessByPasswordUseCase,
): KeyguardPasswordPrompt = {
    awaitKeyguardAuthorityPassword(
        confirmationRouteFactory = confirmationRouteFactory,
        confirmAccessByPassword = confirmAccessByPassword,
    )
}
