package com.artemchep.keyguard.feature.changepassword

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.finally
import com.artemchep.keyguard.common.io.handleErrorTap
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.StringResource

/**
 * Changes the app password or the key derivation of the vault, see
 * [VaultState.Main.ChangePassword.WithPassword.getCreateIo] for the arguments.
 */
internal typealias ChangePasswordAction = (
    currentPassword: String,
    change: VaultState.Main.ChangePassword.Change,
) -> Unit

/**
 * Creates the action that changes the app password or the key derivation
 * of the [state]. A change runs one at a time, see [launchChangePassword].
 *
 * @param successMessage the toast to show once the change completes.
 */
internal fun RememberStateFlowScope.createChangePasswordAction(
    state: VaultState.Main,
    windowCoroutineScope: WindowCoroutineScope,
    loadingSink: MutableStateFlow<Boolean>,
    successMessage: StringResource = Res.string.changepassword_password_changed_successfully,
): ChangePasswordAction = { currentPassword, change ->
    launchChangePassword(
        io = state.changePassword.withMasterPassword
            .getCreateIo(currentPassword, change),
        windowCoroutineScope = windowCoroutineScope,
        loadingSink = loadingSink,
        successMessage = successMessage,
    )
}

/**
 * Runs the password change [io], unless one runs already: a second one is ignored. Shows
 * the [loadingSink] meanwhile. Once the password changes, closes the screen and tells what
 * the change did not carry over. A failure is shown as a message and keeps the screen.
 */
private fun RememberStateFlowScope.launchChangePassword(
    io: IO<VaultState.Main.ChangePassword.Report>,
    windowCoroutineScope: WindowCoroutineScope,
    loadingSink: MutableStateFlow<Boolean>,
    successMessage: StringResource,
) {
    if (!loadingSink.compareAndSet(expect = false, update = true)) {
        return
    }
    io
        .effectTap { report ->
            val msg = ToastMessage(
                title = translate(successMessage),
                type = ToastMessage.Type.SUCCESS,
            )
            message(msg)
            val notResealed = report.authoritiesNotResealed
            if (notResealed > 0) {
                val warning = ToastMessage(
                    title = translate(
                        Res.plurals.changepassword_authority_not_resealed_plural,
                        notResealed,
                        notResealed,
                    ),
                    type = ToastMessage.Type.ERROR,
                )
                message(warning)
            }
            // Pop the screen
            navigatePopSelf()
        }
        .handleErrorTap { e ->
            message(e)
        }
        .finally {
            loadingSink.value = false
        }
        .attempt()
        .launchIn(windowCoroutineScope)
}
