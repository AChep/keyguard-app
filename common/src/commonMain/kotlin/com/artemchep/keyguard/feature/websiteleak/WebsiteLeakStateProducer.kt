package com.artemchep.keyguard.feature.websiteleak

import androidx.compose.runtime.Composable
import arrow.core.getOrElse
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.hibp.breaches.all.BreachesRepository
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetBreaches
import com.artemchep.keyguard.common.usecase.impl.isSubdomain
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import org.koin.compose.currentKoinScope

@Composable
fun produceWebsiteLeakState(
    args: WebsiteLeakRoute.Args,
) = with(currentKoinScope()) {
    produceWebsiteLeakState(
        args = args,
        getBreaches = get(),
        dateFormatter = get(),
    )
}

@Composable
fun produceWebsiteLeakState(
    args: WebsiteLeakRoute.Args,
    getBreaches: GetBreaches,
    dateFormatter: DateFormatter,
): Loadable<WebsiteLeakState> = produceScreenState(
    key = "website_leak",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    websiteLeakStateProducer(
        args = args,
        getBreaches = getBreaches,
        dateFormatter = dateFormatter,
    ).map { state -> Loadable.Ok(state) }
}

suspend fun RememberStateFlowScope.websiteLeakStateProducer(
    args: WebsiteLeakRoute.Args,
    getBreaches: GetBreaches,
    dateFormatter: DateFormatter,
): Flow<WebsiteLeakState> {
    val breaches2 = getBreaches(false)
        .attempt()
        .bind()
    val breach3 = breaches2
        .map {
            it.breaches
                .filter {
                    isBreachDomainMatch(
                        host = args.host,
                        domain = it.domain,
                    )
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
    return flowOf(state)
}

/**
 * Matches a breach domain against a host label-wise: the host must be
 * equal to the domain or one of its subdomains
 */
internal fun isBreachDomainMatch(
    host: String,
    domain: String?,
): Boolean {
    if (domain.isNullOrBlank()) {
        return false
    }
    return isSubdomain(
        domain = domain.lowercase(),
        request = host.lowercase(),
    )
}
