package com.artemchep.keyguard.feature.emailleak

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.CheckUsernameLeakRequest
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.CheckUsernameLeak
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun produceEmailLeakState(
    args: EmailLeakRoute.Args,
) = with(localDI().direct) {
    produceEmailLeakState(
        args = args,
        checkUsernameLeak = instance(),
        dateFormatter = instance(),
    )
}

@Composable
fun produceEmailLeakState(
    args: EmailLeakRoute.Args,
    checkUsernameLeak: CheckUsernameLeak,
    dateFormatter: DateFormatter,
): Loadable<EmailLeakState> = produceScreenState(
    key = "email_leak",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    emailLeakStateProducer(
        args = args,
        checkUsernameLeak = checkUsernameLeak,
        dateFormatter = dateFormatter,
    ).map { state -> Loadable.Ok(state) }
}

suspend fun RememberStateFlowScope.emailLeakStateProducer(
    args: EmailLeakRoute.Args,
    checkUsernameLeak: CheckUsernameLeak,
    dateFormatter: DateFormatter,
): Flow<EmailLeakState> {
    val request = CheckUsernameLeakRequest(
        accountId = args.accountId,
        username = args.email,
    )
    val content = checkUsernameLeak(request)
        .map { report ->
            val breaches = report.leaks
                .sortedByDescending { it.reportedAt }
                .map { leak ->
                    EmailLeakState.Breach(
                        title = leak.title,
                        domain = leak.website,
                        icon = leak.icon,
                        count = leak.count,
                        description = leak.description,
                        occurredAt = leak.occurredAt
                            ?.let(dateFormatter::formatDate),
                        reportedAt = leak.reportedAt
                            ?.let(dateFormatter::formatDate),
                        dataClasses = leak.dataClasses,
                    )
                }
                .toImmutableList()
            EmailLeakState.Content(
                breaches = breaches,
            )
        }
        .attempt()
        .bind()
    val state = EmailLeakState(
        username = args.email,
        content = content,
        onClose = {
            navigatePopSelf()
        },
    )
    return flowOf(state)
}
