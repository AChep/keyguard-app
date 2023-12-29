package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.CheckUsernameLeakRequest
import com.artemchep.keyguard.common.model.DHibp
import com.artemchep.keyguard.common.model.DHibpC
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.CheckUsernameLeak
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.breach
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenTokenRepository
import com.artemchep.keyguard.provider.bitwarden.usecase.util.withRefreshableAccessToken
import io.ktor.client.HttpClient
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class CheckUsernameLeakImpl(
    private val tokenRepository: BitwardenTokenRepository,
    private val base64Service: Base64Service,
    private val json: Json,
    private val httpClient: HttpClient,
    private val db: DatabaseManager,
) : CheckUsernameLeak {
    companion object {
        private const val TAG = "CheckUsernameLeak.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        tokenRepository = directDI.instance(),
        base64Service = directDI.instance(),
        json = directDI.instance(),
        httpClient = directDI.instance(),
        db = directDI.instance(),
    )

    override fun invoke(
        request: CheckUsernameLeakRequest,
    ): IO<DHibpC> = ioEffect {
        val user = tokenRepository.getById(request.accountId)
            .bind()
        requireNotNull(user) {
            "Failed to find a token!"
        }

        val username = request.username
        val breaches = withRefreshableAccessToken(
            base64Service = base64Service,
            httpClient = httpClient,
            json = json,
            db = db,
            user = user,
        ) { latestUser ->
            val serverEnv = latestUser.env.back()
            val accessToken = requireNotNull(latestUser.token).accessToken
            serverEnv.api.hibp.breach(
                httpClient = httpClient,
                env = serverEnv,
                token = accessToken,
                username = username,
            )
        }

        val f = breaches
            .map {
                DHibp(
                    title = it.title.orEmpty(),
                    name = it.name.orEmpty(),
                    description = it.description
                        .orEmpty()
                        .replace("^(<br/>|\\s)+".toRegex(), "")
                        .replace("(<br/>|\\s)+$".toRegex(), ""),
                    website = it.domain.orEmpty(),
                    icon = it.logoPath.orEmpty(),
                    count = it.pwnCount,
                    occurredAt = it.breachDate?.atStartOfDayIn(TimeZone.UTC),
                    reportedAt = it.addedDate?.atStartOfDayIn(TimeZone.UTC),
                    dataClasses = it.dataClasses,
                )
            }
        DHibpC(f)
    }

}
