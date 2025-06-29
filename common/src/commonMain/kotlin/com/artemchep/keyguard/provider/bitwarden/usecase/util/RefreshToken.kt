package com.artemchep.keyguard.provider.bitwarden.usecase.util

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.provider.bitwarden.api.refresh
import com.artemchep.keyguard.common.util.canRetry
import com.artemchep.keyguard.common.util.getHttpCode
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlin.math.pow

private val mutex = Mutex()

private val mutexPerUserId = mutableMapOf<String, Mutex>()

class RetryDelayStrategy {
    private var _attempt: Int = 0

    val attempt get() = _attempt

    suspend fun dl() {
        // If the server is broken and doesn't not recognized our token,
        // e.g. https://github.com/dani-garcia/vaultwarden/issues/3776
        // then artificially delay each request so we don't span the
        // refresh token too much.
        val ms = 100L * (3f.pow(attempt.coerceAtMost(6)) - 1f).toLong()
        delay(ms)
        // Increment the attempt.
        _attempt += 1
    }
}

internal suspend fun <T> withRetry(
    block: suspend () -> T,
): T {
    val retryDelayStrategy = RetryDelayStrategy()
    while (true) {
        try {
            return block()
        } catch (e: Exception) {
            e.printStackTrace()
            val statusCode = e.getHttpCode()

            val canRetry = statusCode.canRetry() &&
                    statusCode != BitwardenService.Error.CODE_UNKNOWN
            if (!canRetry) {
                throw e
            }
        }

        retryDelayStrategy.dl()
    }
}

internal suspend fun <T> withRefreshableAccessToken(
    base64Service: Base64Service,
    httpClient: HttpClient,
    json: Json,
    db: DatabaseManager,
    user: BitwardenToken,
    block: suspend (BitwardenToken) -> T,
): T {
    val getAndUpdateUserToken: suspend (BitwardenToken) -> BitwardenToken = {
        val m = mutex.withLock {
            // Get or create a mutex specific to this
            // account. We never delete it because we do
            // not expect a user creating & deleting dozens
            // of accounts.
            mutexPerUserId.getOrPut(user.id) { Mutex() }
        }
        m.withLock {
            getAndUpdateUserToken(
                base64Service = base64Service,
                httpClient = httpClient,
                json = json,
                db = db,
                user = it,
            )
        }
    }

    val retryDelayStrategy = RetryDelayStrategy()
    var latestToken = user
    while (true) {
        val token = user.token
        if (token != null) {
            // Refresh token immediately if the expiration date
            // has already passed.
            if (Clock.System.now() > token.expirationDate) {
                latestToken = getAndUpdateUserToken(latestToken)
            }
        }

        try {
            return block(latestToken)
        } catch (e: Exception) {
            val statusCode = e.getHttpCode()
            if (statusCode == HttpStatusCode.Unauthorized.value) {
                latestToken = getAndUpdateUserToken(latestToken)
            } else {
                throw e
            }
        }

        retryDelayStrategy.dl()
    }
}

suspend fun getAndUpdateUserToken(
    base64Service: Base64Service,
    httpClient: HttpClient,
    json: Json,
    db: DatabaseManager,
    user: BitwardenToken,
): BitwardenToken {
    if (user.token == null) {
        throw IllegalStateException("Help")
    }

    val newUser = db.mutate("RefreshToken") {
        it.accountQueries
            .getByAccountId(user.id)
            .executeAsOneOrNull()
    }.bind()
    val newUserToken = newUser?.data_
    if (newUserToken != null && newUserToken != user) {
        return newUserToken
    }

    // TODO: This one may also crash the server!
    val login = refresh(
        base64Service = base64Service,
        httpClient = httpClient,
        json = json,
        env = user.env.back(),
        token = user.token,
    )
    val token = BitwardenToken.Token(
        refreshToken = login.refreshToken,
        accessToken = login.accessToken,
        expirationDate = login.accessTokenExpiryDate,
    )
    val u = user.copy(token = token)
    db.mutate("RefreshToken") {
        it.accountQueries.insert(
            accountId = u.id,
            data = u,
        )
    }.bind()
    return u
}
