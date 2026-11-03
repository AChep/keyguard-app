package com.artemchep.keyguard.integration.bitwarden.cli

import com.artemchep.keyguard.integration.bitwarden.TestAccount
import com.artemchep.keyguard.integration.bitwarden.TestKdf
import com.artemchep.keyguard.integration.bitwarden.VaultwardenApi
import com.artemchep.keyguard.integration.bitwarden.requireJson
import com.artemchep.keyguard.integration.bitwarden.setJsonBody
import com.artemchep.keyguard.integration.bitwarden.toTestKdf
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.sync
import com.artemchep.keyguard.provider.bitwarden.entity.AccountPreLoginResponse
import com.artemchep.keyguard.provider.bitwarden.entity.toDomain
import io.ktor.client.request.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Unlocks an existing account the way Keyguard does: Keyguard's `login` derives the master key
 * with the KDF of the prelogin, then the sync profile yields the user key and the private key.
 * Fails on an account with two-step login.
 */
internal suspend fun VaultwardenApi.unlock(email: String, password: String): UnlockedAccount {
    val (secrets, login) = keyguardPasswordLogin(email = email, password = password)
    val profile = env.api.sync(httpClient, env, login.accessToken).profile
    val userKey = accounts.decrypt(secrets.encryptionKey + secrets.macKey, profile.key)
    val account = TestAccount(
        email = email,
        password = password,
        kdf = prelogin(email),
        masterKey = secrets.masterKey,
        masterPasswordHash = base64Service.encodeToString(secrets.passwordKey),
        userKey = userKey,
        privateKey = accounts.decrypt(userKey, profile.privateKey),
    )
    val organizationKeys = profile.organizations.orEmpty().associate { it.id to it.key }
    return UnlockedAccount(account = account, accessToken = login.accessToken, organizationKeys = organizationKeys)
}

/** An account this process did not register, with the secrets that [unlock] recovered. */
internal class UnlockedAccount(
    val account: TestAccount,
    val accessToken: String,
    /** Organization keys encrypted for this account, as returned by the sync profile. */
    val organizationKeys: Map<String, String>,
)

/** The KDF of [email], decoded by Keyguard's own prelogin model. */
private suspend fun VaultwardenApi.prelogin(email: String): TestKdf {
    val response = httpClient
        .post("$baseUrl/identity/accounts/prelogin") {
            setJsonBody(buildJsonObject { put("email", email.trim().lowercase()) })
        }
        .requireJson("Prelogin $email")
    return json
        .decodeFromJsonElement(AccountPreLoginResponse.serializer(), response)
        .toDomain(email)
        .hash
        .toTestKdf()
}
