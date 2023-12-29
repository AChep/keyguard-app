package com.artemchep.keyguard.common.service.hibp.breaches.find.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.mutex
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DHibp
import com.artemchep.keyguard.common.model.DHibpC
import com.artemchep.keyguard.common.model.UsernamePwnage
import com.artemchep.keyguard.common.service.hibp.breaches.find.AccountPwnageDataSourceRemote
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.breach
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenTokenRepository
import com.artemchep.keyguard.provider.bitwarden.usecase.util.withRefreshableAccessToken
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class AccountPwnageDataSourceRemoteImpl(
    private val tokenRepository: BitwardenTokenRepository,
    private val base64Service: Base64Service,
    private val json: Json,
    private val httpClient: HttpClient,
    private val db: DatabaseManager,
) : AccountPwnageDataSourceRemote {
    companion object {
        private const val DEFAULT_RETRY_AFTER_MS = 1000L
    }

    private val mutex = Mutex()

    constructor(directDI: DirectDI) : this(
        tokenRepository = directDI.instance(),
        base64Service = directDI.instance(),
        json = directDI.instance(),
        httpClient = directDI.instance(),
        db = directDI.instance(),
    )

    override fun check(
        accountId: AccountId,
        username: String,
    ): IO<UsernamePwnage> = ioEffect {
        val user = tokenRepository.getById(accountId)
            .bind()
        requireNotNull(user) {
            "Failed to find a token!"
        }

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
    }.mutex(mutex)
}
