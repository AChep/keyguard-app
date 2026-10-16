package com.artemchep.keyguard.integration.bitwarden

import com.artemchep.keyguard.common.exception.ApiException
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.configureBitwardenHttpRetry
import com.artemchep.keyguard.provider.bitwarden.api.builder.post
import com.artemchep.keyguard.provider.bitwarden.entity.SendTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.request.SendRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.SendTextRequest
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** Server answers Keyguard's client has to survive. Each test configures its own server. */
class KeyguardErrorPathsE2eTest {
    @Test
    fun `login retries after a rate limit`() = VaultwardenFixture
        .start(
            overrides = mapOf(
                // One token request per client address, then one every 3 s. The second
                // login's token request lands inside that window; its retries, 1-2 s
                // apart, outlast it.
                "LOGIN_RATELIMIT_MAX_BURST" to "1",
                "LOGIN_RATELIMIT_SECONDS" to "3",
            ),
        )
        .use { fixture ->
            fixture.runE2eTest {
                val account = accounts.register()
                val delays = mutableListOf<Long>()
                retryingClient(delays).use { client ->
                    suspend fun login() = keyguardPasswordLogin(account.email, account.password, httpClient = client)

                    val (secrets, firstLogin) = login()
                    assertContentEquals(account.masterKey, secrets.masterKey)
                    assertTrue(firstLogin.accessToken.isNotBlank())
                    assertEquals(emptyList(), delays, "The first login must not need a retry")

                    val (_, secondLogin) = login()
                    assertTrue(secondLogin.accessToken.isNotBlank())
                    assertTrue(delays.isNotEmpty(), "The second login must be retried after a 429")
                }
            }
        }

    @Test
    fun `sends disabled is reported as an api error`() = VaultwardenFixture
        .start(overrides = mapOf("SENDS_ALLOWED" to "false"))
        .use { fixture ->
            fixture.runE2eTest {
                val account = accounts.register()
                val session = keyguardLogin(account)
                val request = SendRequest(
                    // Any well-formed EncStrings do; the server refuses the Send whatever it holds.
                    key = accounts.encrypt(account, "key material"),
                    type = SendTypeEntity.Text,
                    name = accounts.encrypt(account, "Keyguard E2E"),
                    notes = null,
                    disabled = false,
                    hideEmail = false,
                    deletionDate = Clock.System.now() + 1.days,
                    expirationDate = null,
                    maxAccessCount = null,
                    text = SendTextRequest(text = accounts.encrypt(account, "Hello"), hidden = false),
                    file = null,
                )

                val failure = assertFailsWith<ApiException> {
                    session.env.api.sends.post(httpClient, session.env, session.login.accessToken, request)
                }
                assertEquals(HttpStatusCode.BadRequest, failure.code)
                assertTrue(failure.message.orEmpty().isNotBlank())
            }
        }

    /** Keyguard's client with its retry policy, reporting every retry delay to [delays]. */
    private fun VaultwardenFixture.retryingClient(delays: MutableList<Long>): HttpClient {
        val recordingDelay: suspend (Long) -> Unit = { millis ->
            delays += millis
            // The hook replaces Ktor's own delay, so it waits itself.
            delay(millis)
        }
        return newHttpClient {
            install(HttpRequestRetry) {
                configureBitwardenHttpRetry(recordingDelay)
            }
        }
    }
}
