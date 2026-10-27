package com.artemchep.keyguard.integration.bitwarden

import arrow.core.getOrElse
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.service.totp.TotpService
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Two-factor setup over the Bitwarden client API.
 *
 * Vaultwarden's TOTP is SHA-1, 30 s, 6 digits, with a drift of one step either way. A code is
 * accepted only if its step is later than the last accepted one, and enabling the authenticator
 * accepts the current step. So after [enableTotp], log in with `totpCode(secret, stepOffset = 1)`;
 * a second TOTP login needs a later step still, or a remember token.
 */
internal class VaultwardenTwoFactor(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val totpService: TotpService,
) {
    /** Enables the authenticator app provider and returns its base32 secret. */
    suspend fun enableTotp(account: TestAccount, accessToken: String): String {
        val secret = httpClient
            .post("$baseUrl/api/two-factor/get-authenticator") {
                bearerAuth(accessToken)
                setJsonBody(
                    buildJsonObject {
                        put("masterPasswordHash", account.masterPasswordHash)
                    },
                )
            }
            .requireJson("Get an authenticator secret")
            .jsonObject
            .string("key")
        httpClient
            .post("$baseUrl/api/two-factor/authenticator") {
                bearerAuth(accessToken)
                setJsonBody(
                    buildJsonObject {
                        put("key", secret)
                        put("token", totpCode(secret))
                        put("masterPasswordHash", account.masterPasswordHash)
                    },
                )
            }
            .requireSuccess("Enable the authenticator")
        return secret
    }

    /** Keyguard's code for the step [stepOffset] steps after the current one. */
    fun totpCode(secret: String, stepOffset: Int = 0): String = totpCode(totpService, secret, stepOffset)
}

/** Keyguard's code of a base32 [secret] for the step [stepOffset] steps after the current one. */
internal fun totpCode(totpService: TotpService, secret: String, stepOffset: Int = 0): String {
    val token = TotpToken.parse(secret).getOrElse { throw it }
    // Keyguard rounds the time to the nearest second, so the start of the
    // step keeps it from computing the next step in the last half second.
    val stepStart = Clock.System.now().epochSeconds / TOTP_PERIOD_SECONDS * TOTP_PERIOD_SECONDS
    return totpService
        .generate(
            token = token,
            timestamp = Instant.fromEpochSeconds(stepStart),
            offset = stepOffset,
        )
        .getOrElse { throw it }
        .code
}

/** Seconds until the next TOTP step starts. */
internal fun totpSecondsLeft(): Long = TOTP_PERIOD_SECONDS - Clock.System.now().epochSeconds % TOTP_PERIOD_SECONDS

private const val TOTP_PERIOD_SECONDS = 30L
