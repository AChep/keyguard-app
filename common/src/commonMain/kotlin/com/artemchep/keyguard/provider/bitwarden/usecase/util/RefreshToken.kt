package com.artemchep.keyguard.provider.bitwarden.usecase.util

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.util.ReconnectBackoff
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.provider.bitwarden.api.refresh
import com.artemchep.keyguard.common.util.getHttpCode
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlinx.serialization.json.Json

private val mutex = Mutex()

private val mutexPerUserId = mutableMapOf<String, Mutex>()

internal suspend fun <T> withRefreshableAccessToken(
    base64Service: Base64Service,
    httpClient: HttpClient,
    json: Json,
    db: VaultDatabaseManager,
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

    val reconnectBackoff = ReconnectBackoff()

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

        delay(reconnectBackoff.nextDelayMs())
    }
}

suspend fun getAndUpdateUserToken(
    base64Service: Base64Service,
    httpClient: HttpClient,
    json: Json,
    db: VaultDatabaseManager,
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
    val newUserToken = newUser?.data_ as BitwardenToken?
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
