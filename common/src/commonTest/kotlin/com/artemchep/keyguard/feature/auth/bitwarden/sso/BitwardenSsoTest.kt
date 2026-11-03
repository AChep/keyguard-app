package com.artemchep.keyguard.feature.auth.bitwarden.sso

import com.artemchep.keyguard.common.service.serialization.createApplicationJson
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.entity.ConnectTokenResponse
import com.artemchep.keyguard.provider.bitwarden.entity.ErrorEntity
import com.artemchep.keyguard.provider.bitwarden.entity.toException
import com.artemchep.keyguard.common.exception.ApiException
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.parseQueryString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class BitwardenSsoTest {
    private val request = SsoRequest(
        env = ServerEnv(baseUrl = "https://vault.example.test/path/"),
        email = "a+b@example.test", state = "unguessable-state", verifier = "verifier", challenge = "challenge",
    )

    @Test
    fun browserHandoffUsesWebVaultAndEscapesInputs() {
        val url = Url(request.browserUrl("http://localhost:8065"))
        assertEquals("/path/", url.encodedPath)
        val encodedQuery = request.browserUrl("http://localhost:8065").substringAfter('#').substringAfter('?')
        val query = parseQueryString(encodedQuery)
        assertEquals("desktop", query["clientId"])
        assertEquals("http://localhost:8065", query["redirectUri"])
        assertEquals("a+b@example.test", query["email"])
        assertEquals("challenge", query["codeChallenge"])
        assertFalse(url.toString().contains("verifier"))
    }

    @Test
    fun acceptsNativeAndLoopbackCallbacksWithOrganizationSuffix() {
        for (redirect in listOf(SsoRequest.NATIVE_CALLBACK, "http://localhost:8065")) {
            val result = request.accept("$redirect?code=secret&state=unguessable-state_identifier=org", redirect)
            assertEquals("secret", result.code)
            assertEquals("org", result.organizationIdentifier)
            assertEquals(redirect, result.redirectUri)
        }
    }

    @Test
    fun rejectsForgedMalformedAndAmbiguousCallbacks() {
        val redirect = SsoRequest.NATIVE_CALLBACK
        val invalid = listOf(
            "$redirect?code=secret&state=wrong",
            "$redirect?code=secret&state=unguessable-state-extra",
            "$redirect?code=secret&state=unguessable-state&state=unguessable-state",
            "$redirect?code=one&code=two&state=unguessable-state",
            "$redirect?code=&state=unguessable-state",
            "$redirect?state=unguessable-state",
            "$redirect/other?code=secret&state=unguessable-state",
            "bitwarden://other?code=secret&state=unguessable-state",
            "$redirect?code=secret&state=unguessable-state#error",
            "$redirect?error=denied&state=unguessable-state",
        )
        invalid.forEach { callback -> assertFailsWith<SsoException> { request.accept(callback, redirect) } }
    }

    @Test
    fun promptCompletesOnceAndCannotResumeAfterCancellation() {
        val redirect = SsoRequest.NATIVE_CALLBACK
        val prompt = SsoBrowserPrompt(request)
        prompt.cancel()
        prompt.complete("$redirect?code=secret&state=unguessable-state", redirect)
        assertEquals(true, prompt.result.isCancelled)
    }

    @Test
    fun tokenResponseAcceptsModernAndLegacyFieldNames() {
        val json = Json { ignoreUnknownKeys = true }
        val token = """"access_token":"access","token_type":"Bearer","expires_in":3600,"refresh_token":"refresh""""
        for (key in listOf("MasterKeyWrappedUserKey", "MasterKeyEncryptedUserKey", "masterKeyWrappedUserKey")) {
            val response = json.decodeFromString<ConnectTokenResponse>("""{
                $token,
                "UserDecryptionOptions":{"HasMasterPassword":true,"MasterPasswordUnlock":{
                    "Salt":"custom-salt","Kdf":{"KdfType":1,"Iterations":3,"Memory":64,"Parallelism":4},
                    "$key":"wrapped-key"
                }}
            }""")
            val unlock = assertNotNull(response.userDecryptionOptions?.masterPasswordUnlock)
            assertEquals("custom-salt", unlock.salt)
            assertEquals(64, unlock.kdf.memory)
            assertEquals("wrapped-key", unlock.wrappedUserKey)
        }
        val legacy = json.decodeFromString<ConnectTokenResponse>(
            """{$token,"key":"legacy-key","kdf":0,"kdfIterations":600000}""",
        )
        assertEquals("legacy-key", legacy.key)
        assertEquals(600000, legacy.kdfIterationsCount)
    }

    @Test
    fun twoFactorChallengeRetainsSsoEmailProof() {
        val error = createApplicationJson().decodeFromString<ErrorEntity>("""{
            "error":"invalid_grant","TwoFactorProviders":[1],
            "SsoEmail2faSessionToken":"proof","Email":"account@example.test"
        }""")
        val exception = error.toException(Exception(), HttpStatusCode.BadRequest)
        val challenge = exception.type as ApiException.Type.TwoFaRequired
        assertEquals("proof", challenge.ssoEmailSessionToken)
        assertEquals("account@example.test", challenge.email)
    }
}
