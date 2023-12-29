package com.artemchep.keyguard.feature.websiteleak

import androidx.compose.runtime.Composable
import arrow.core.getOrElse
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.hibp.breaches.all.BreachesRepository
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun produceWebsiteLeakState(
    args: WebsiteLeakRoute.Args,
) = with(localDI().direct) {
    produceWebsiteLeakState(
        args = args,
        breachesRepository = instance(),
        dateFormatter = instance(),
    )
}

@Composable
fun produceWebsiteLeakState(
    args: WebsiteLeakRoute.Args,
    breachesRepository: BreachesRepository,
    dateFormatter: DateFormatter,
): Loadable<WebsiteLeakState> = produceScreenState(
    key = "website_leak",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    val breaches2 = breachesRepository.get()
        .attempt()
        .bind()
    val breach3 = breaches2
        .map {
            it.breaches
                .filter {
                    it.domain != null &&
                            it.domain.isNotBlank() &&
                            args.host.endsWith(it.domain)
                }
                .sortedByDescending { it.addedDate }
                .map { leak ->
                    WebsiteLeakState.Breach(
                        title = leak.title.orEmpty(),
                        domain = leak.domain.orEmpty(),
                        icon = leak.logoPath.orEmpty(),
                        count = leak.pwnCount,
                        description = leak.description.orEmpty(),
                        occurredAt = leak.breachDate
                            ?.atTime(LocalTime.fromMillisecondOfDay(0))
                            ?.toInstant(TimeZone.UTC)
                            ?.let(dateFormatter::formatDate),
                        reportedAt = leak.addedDate
                            ?.atTime(LocalTime.fromMillisecondOfDay(0))
                            ?.toInstant(TimeZone.UTC)
                            ?.let(dateFormatter::formatDate),
                        dataClasses = leak.dataClasses,
                    )
                }
        }
        .getOrElse { emptyList() }

    val content = WebsiteLeakState.Content(
        breaches = breach3
            .toImmutableList(),
    )
    val state = WebsiteLeakState(
        content = content,
        onClose = {
            navigatePopSelf()
        },
    )
    flowOf(Loadable.Ok(state))
}
