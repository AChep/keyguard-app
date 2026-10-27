package com.artemchep.keyguard.integration.bitwarden

import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeRsaOaepHash
import com.artemchep.keyguard.provider.bitwarden.usecase.util.pbk
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal class TestOrganization(
    val id: String,
    /** 64 bytes: the AES key, then the HMAC key. */
    val key: ByteArray,
    /** The collection created with the organization. */
    val collectionId: String,
)

/** A user's membership in an organization. */
internal data class Membership(
    val id: String,
    val userId: String,
    /** [INVITED], [ACCEPTED], [CONFIRMED] or [REVOKED]. */
    val status: Int,
) {
    companion object {
        const val REVOKED = -1
        const val INVITED = 0
        const val ACCEPTED = 1
        const val CONFIRMED = 2
    }
}

/** Organizations over the Bitwarden client API, with the client-side key wrapping. */
internal class VaultwardenOrganizations(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
    private val accounts: VaultwardenAccounts,
) {
    /** Creates an organization owned by [account], with one collection named "Default". */
    suspend fun createOrganization(account: TestAccount, accessToken: String, name: String): TestOrganization {
        val orgKey = cryptoGenerator.seed(length = ORG_KEY_LENGTH)
        val keyPair = newRsaKeyPair()
        val id = httpClient
            .post("$baseUrl/api/organizations") {
                bearerAuth(accessToken)
                setJsonBody(
                    buildJsonObject {
                        put("name", name)
                        put("billingEmail", account.email)
                        put("collectionName", accounts.encrypt(orgKey, DEFAULT_COLLECTION_NAME))
                        put("key", wrapKey(publicKey = pbk(account.privateKey), orgKey = orgKey))
                        putJsonObject("keys") {
                            put("publicKey", base64Service.encodeToString(keyPair.public.encoded))
                            put("encryptedPrivateKey", accounts.encrypt(orgKey, keyPair.private.encoded))
                        }
                        put("planType", 0)
                    },
                )
            }
            .requireJson("Create the organization $name")
            .jsonObject
            .string("id")
        val collectionId = httpClient
            .get("$baseUrl/api/organizations/$id/collections") {
                bearerAuth(accessToken)
            }
            .requireJson("List the collections of $id")
            .jsonObject
            .getValue("data")
            .jsonArray
            .single()
            .jsonObject
            .string("id")
        return TestOrganization(id = id, key = orgKey, collectionId = collectionId)
    }

    /** Invites [email] as a user with access to the default collection. */
    suspend fun invite(ownerAccessToken: String, org: TestOrganization, email: String) {
        httpClient
            .post("$baseUrl/api/organizations/${org.id}/users/invite") {
                bearerAuth(ownerAccessToken)
                setJsonBody(
                    buildJsonObject {
                        putJsonArray("emails") { add(email) }
                        put("type", MEMBER_TYPE_USER)
                        putJsonArray("groups") {}
                        putJsonArray("collections") {
                            addJsonObject {
                                put("id", org.collectionId)
                                put("readOnly", false)
                                put("hidePasswords", false)
                                put("manage", false)
                            }
                        }
                        put("accessAll", false)
                    },
                )
            }
            .requireSuccess("Invite $email to ${org.id}")
    }

    suspend fun membership(ownerAccessToken: String, org: TestOrganization, email: String): Membership {
        val member = httpClient
            .get("$baseUrl/api/organizations/${org.id}/users") {
                bearerAuth(ownerAccessToken)
            }
            .requireJson("List the members of ${org.id}")
            .jsonObject
            .getValue("data")
            .jsonArray
            .map { it.jsonObject }
            .single { it.string("email").equals(email, ignoreCase = true) }
        return Membership(
            id = member.string("id"),
            userId = member.string("userId"),
            status = member.getValue("status").jsonPrimitive.int,
        )
    }

    /** Confirms an accepted member by wrapping the organization key with their public key. */
    suspend fun confirm(
        ownerAccessToken: String,
        org: TestOrganization,
        membershipId: String,
        inviteeUserId: String,
    ) {
        val publicKey = accounts.publicKey(ownerAccessToken, inviteeUserId)
        httpClient
            .post("$baseUrl/api/organizations/${org.id}/users/$membershipId/confirm") {
                bearerAuth(ownerAccessToken)
                setJsonBody(
                    buildJsonObject {
                        put("key", wrapKey(publicKey = publicKey, orgKey = org.key))
                    },
                )
            }
            .requireSuccess("Confirm $membershipId in ${org.id}")
    }

    /** An EncString of type 4: RSA-OAEP with SHA-1. */
    private fun wrapKey(publicKey: ByteArray, orgKey: ByteArray): String {
        val wrapped = NativeCryptoPrimitives.rsaOaepEncrypt(
            publicKeySpki = publicKey,
            plaintext = orgKey,
            hash = NativeRsaOaepHash.SHA_1,
        )
        return "4." + base64Service.encodeToString(wrapped)
    }

    companion object {
        const val DEFAULT_COLLECTION_NAME = "Default"

        private const val ORG_KEY_LENGTH = 64
        private const val MEMBER_TYPE_USER = 2
    }
}
