package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.impl.cxfUrlSafeBase64
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfAccount
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import kotlin.test.assertIs
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shared helpers for the CXF v1.0 spec-conformance suite
 * (cxf-v1.0-ps-errata-20260309). Test-only, no test class of its own.
 */

/**
 * Builds a single account and unwraps it, discarding the skipped counts — a
 * test-only convenience over [CxfExportService.buildAccountResult].
 */
fun CxfExportService.buildAccount(
    profile: DProfile,
    ciphers: List<DSecret>,
    allowedTypes: Set<CxfCredentialType>,
    folders: List<DFolder> = emptyList(),
): CxfAccount? = buildAccountResult(
    profile = profile,
    ciphers = ciphers,
    allowedTypes = allowedTypes,
    folders = folders,
).account

/**
 * Parses a payload that is expected to succeed and returns the plan.
 */
fun CxfImportService.parseSuccessPlan(
    payload: String,
    now: Instant,
): CxfImportPlan {
    val result = parse(
        payload = payload,
        now = now,
    )
    return assertIs<CxfImportResult.Success>(result).plan
}

/**
 * Recursively collects every primitive value stored under [key] anywhere in
 * the JSON tree. Used to sweep an encoded document for `id`, `fieldType` or
 * `type` members regardless of nesting depth.
 */
fun collectJsonMembers(
    element: JsonElement,
    key: String,
): List<JsonPrimitive> = when (element) {
    is JsonObject -> {
        val own = (element[key] as? JsonPrimitive)
            ?.let(::listOf)
            .orEmpty()
        own + element.values.flatMap { collectJsonMembers(it, key) }
    }

    is JsonArray -> element.flatMap { collectJsonMembers(it, key) }
    is JsonPrimitive -> emptyList()
}

/**
 * The decoded byte length of a base64url value; CXF `.size` constraints apply
 * to decoded bytes, not to the encoded string.
 */
fun decodedB64UrlSize(
    value: String,
): Int = cxfUrlSafeBase64.decode(value).size

/**
 * Wraps the given item objects into a minimal valid CXF v1.0 document with a
 * single, collection-less account.
 */
internal fun documentWithItems(
    vararg items: String,
): String = documentWithAccount(
    collectionsJson = "[]",
    itemsJson = "[${items.joinToString(separator = ",")}]",
)

/**
 * Wraps the given collection objects into a minimal valid CXF v1.0 document
 * with a single, item-less account.
 */
internal fun documentWithCollections(
    vararg collections: String,
): String = documentWithAccount(
    collectionsJson = "[${collections.joinToString(separator = ",")}]",
    itemsJson = "[]",
)

/**
 * A minimal valid CXF v1.0 document with one account carrying the given
 * `collections` and `items` members verbatim, so a test can hand either of them
 * a deliberately malformed shape.
 */
internal fun documentWithAccount(
    collectionsJson: String,
    itemsJson: String,
): String = documentWithAccounts(
    """
        {
          "id": "YWNjLTE",
          "username": "Alice Example",
          "email": "alice@example.com",
          "collections": $collectionsJson,
          "items": $itemsJson
        }
    """.trimIndent(),
)

/**
 * A well-formed CXF `passkey` credential object. The defaults decode as valid
 * base64url, so a test only overrides the member it wants to break.
 */
internal fun cxfPasskeyCredentialJson(
    credentialId: String = "AAECAwQFBg",
): String = """
    {
      "type": "passkey",
      "credentialId": "$credentialId",
      "rpId": "example.com",
      "username": "alice",
      "userDisplayName": "Alice",
      "userHandle": "AAECAwQFBg",
      "key": "$CXF_TEST_PASSKEY_KEY_URL"
    }
""".trimIndent()

/**
 * A well-formed CXF `totp` credential object carrying the otpauth defaults.
 */
internal fun cxfTotpCredentialJson(
    secret: String = "JBSWY3DPEHPK3PXP",
    period: Int = 30,
    digits: Int = 6,
    algorithm: String = "sha1",
): String = """
    {
      "type": "totp",
      "secret": "$secret",
      "period": $period,
      "digits": $digits,
      "algorithm": "$algorithm"
    }
""".trimIndent()

/**
 * A well-formed CXF `ssh-key` credential object.
 */
internal fun cxfSshKeyCredentialJson(
    privateKey: String = "AAECAwQFBg",
): String = """{"type": "ssh-key", "keyType": "ssh-ed25519", "privateKey": "$privateKey"}"""

/**
 * A minimal valid CXF v1.0 document whose `accounts` array holds the given
 * entries verbatim — including entries that are not objects at all, which is
 * what the account-level skip accounting is about.
 */
internal fun documentWithAccounts(
    vararg accountsJson: String,
): String = """
    {
      "version": {"major": 1, "minor": 0},
      "exporterRpId": "com.example.exporter",
      "exporterDisplayName": "Example Exporter",
      "timestamp": 1706623773,
      "accounts": [${accountsJson.joinToString(separator = ",")}]
    }
""".trimIndent()
