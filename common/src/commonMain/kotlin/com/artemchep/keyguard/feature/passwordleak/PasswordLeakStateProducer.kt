package com.artemchep.keyguard.feature.passwordleak

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.CheckPasswordLeakRequest
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.CheckPasswordLeak
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun producePasswordLeakState(
    args: PasswordLeakRoute.Args,
) = with(localDI().direct) {
    producePasswordLeakState(
        args = args,
        checkPasswordLeak = instance(),
    )
}

@Composable
fun producePasswordLeakState(
    args: PasswordLeakRoute.Args,
    checkPasswordLeak: CheckPasswordLeak,
): Loadable<PasswordLeakState> = produceScreenState(
    key = "password_leak",
    initial = Loadable.Loading,
    args = arrayOf(
        checkPasswordLeak,
    ),
) {
    val request = CheckPasswordLeakRequest(
        password = args.password,
    )
    val content = checkPasswordLeak(request)
        .map { occurrences ->
            PasswordLeakState.Content(
                occurrences = occurrences,
            )
        }
        .attempt()
        .bind()

    val state = PasswordLeakState(
        content = content,
        onClose = {
            navigatePopSelf()
        },
    )
    flowOf(Loadable.Ok(state))
}
