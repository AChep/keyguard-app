package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.util.hexToByteArray
import com.artemchep.keyguard.util.messagepack.MessagePackCodec
import com.artemchep.keyguard.util.webauthn.PasskeyBase64
import com.artemchep.keyguard.util.webauthn.crypto.NativePasskeyCrypto
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyKeyInspectionResult
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyPublicKey
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ProtonPassPasskeyImportTest {
    @Test
    fun `a proton pass passkey imports with its private key`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.ProtonPass,
            text = protonExport("Site" to listOf(protonPasskey(protonPasskeyContent()))),
        )
        plan.assertVaultStorable()
        assertTrue(plan.skips.isEmpty)

        val login = plan.items.single().request
        assertEquals("alice", login.login.username)
        val passkey = login.fido2Credentials.single()
        // A 16-byte id is stored in the UUID form.
        assertEquals("00010203-0405-0607-0809-0a0b0c0d0e0f", passkey.credentialId)
        assertEquals("webauthn.io", passkey.rpId)
        assertEquals("WebAuthn.io", passkey.rpName)
        assertEquals("alice", passkey.userName)
        assertEquals("Alice", passkey.userDisplayName)
        assertEquals(PasskeyBase64.encodeToString(USER_HANDLE), passkey.userHandle)
        assertTrue(passkey.discoverable)
        assertEquals(0, passkey.counter)
        assertEquals(Instant.fromEpochSeconds(1_714_982_805), passkey.creationDate)
        assertEquals("ECDSA", passkey.keyAlgorithm)
        assertEquals("P-256", passkey.keyCurve)
        assertNull(passkey.prfSecret)

        val pkcs8 = PasskeyBase64.decodeStoredKeyOrNull(passkey.keyValue)!!
        val material = assertIs<PasskeyKeyInspectionResult.Success>(NativePasskeyCrypto.inspect(pkcs8))
            .keyMaterial
        val publicKey = assertIs<PasskeyPublicKey.EcP256>(material.publicKey)
        assertContentEquals(P256_X, publicKey.x)
        assertContentEquals(P256_Y, publicKey.y)
    }

    @Test
    fun `a proton pass passkey keeps its counter and prf seed`() = runTest {
        val seed = ByteArray(32) { 7 }
        val content = protonPasskeyContent(
            counter = 42,
            extensions = mapOf(
                "hmac_secret" to mapOf(
                    "cred_with_uv" to seed.toIntList(),
                    "cred_without_uv" to null,
                ),
            ),
        )
        val plan = parseImportPlan(
            format = ImportFormatId.ProtonPass,
            text = protonExport("Site" to listOf(protonPasskey(content))),
        )
        assertTrue(plan.skips.isEmpty)

        val passkey = plan.items.single().request.fido2Credentials.single()
        assertEquals(42, passkey.counter)
        assertEquals(PasskeyBase64.encodeToString(seed), passkey.prfSecret)
        assertNull(passkey.prfSecretWithoutUv)
    }

    @Test
    fun `an older proton pass passkey takes its user handle from the user id`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.ProtonPass,
            text = protonExport(
                "Site" to listOf(
                    protonPasskey(
                        content = protonPasskeyContent(userHandle = null),
                        userHandle = "",
                    ),
                ),
            ),
        )
        assertTrue(plan.skips.isEmpty)

        val passkey = plan.items.single().request.fido2Credentials.single()
        assertEquals(PasskeyBase64.encodeToString(USER_HANDLE), passkey.userHandle)
        assertTrue(passkey.discoverable)
    }

    @Test
    fun `a proton pass passkey without any user handle imports as non-discoverable`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.ProtonPass,
            text = protonExport(
                "Site" to listOf(
                    protonPasskey(
                        content = protonPasskeyContent(userHandle = null),
                        userHandle = "",
                        userId = "",
                    ),
                ),
            ),
        )
        assertTrue(plan.skips.isEmpty)

        val passkey = plan.items.single().request.fido2Credentials.single()
        assertNull(passkey.userHandle)
        assertFalse(passkey.discoverable)
    }

    @Test
    fun `a proton pass passkey that cannot be rebuilt is counted and its login kept`() = runTest {
        // An array32 header that claims 2^31 values in six bytes.
        val hostile = byteArrayOf(0xdd.toByte(), 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x01)
        val cases = mapOf(
            "Ed25519" to protonPasskey(protonPasskeyContent(kty = "OKP")),
            "Off the curve" to protonPasskey(protonPasskeyContent(x = P256_Y, y = P256_X)),
            "Wrong scalar" to protonPasskey(protonPasskeyContent(d = ByteArray(32) { 1 })),
            "Next format" to protonPasskey(protonPasskeyContent(version = 2)),
            "Not base64" to protonPasskey("%%%"),
            "Hostile envelope" to protonPasskey(Base64.encode(hostile)),
            "Hostile content" to protonPasskey(protonPasskeyEnvelope(hostile)),
            "No content" to protonPasskey(null),
            // A member that does not decode is not passed over for the next.
            "Broken uhd" to protonPasskey(protonPasskeyContent(userHandle = listOf(0x100))),
            "Broken user handle" to protonPasskey(
                content = protonPasskeyContent(userHandle = null),
                userHandle = "%%%",
            ),
            "Broken user id" to protonPasskey(
                content = protonPasskeyContent(userHandle = null),
                userHandle = "",
                userId = "%%%",
            ),
            "Broken PRF seed" to protonPasskey(
                protonPasskeyContent(
                    extensions = mapOf(
                        "hmac_secret" to mapOf(
                            "cred_with_uv" to ByteArray(32) { 7 }.toIntList(),
                            "cred_without_uv" to listOf(0x100),
                        ),
                    ),
                ),
            ),
        )
        val plan = parseImportPlan(
            format = ImportFormatId.ProtonPass,
            text = protonExport(*cases.map { (title, passkey) -> title to listOf(passkey) }.toTypedArray()),
        )
        plan.assertVaultStorable()

        assertEquals(cases.keys.toList(), plan.items.map { it.request.title })
        assertTrue(plan.items.all { it.request.fido2Credentials.isEmpty() })
        assertTrue(plan.items.all { it.request.login.username == "alice" })
        assertEquals(importSkips(ImportSkipReason.Passkey to cases.size), plan.skips)
        assertEquals(cases.keys.associateWith { 1 }, plan.skips.titlesOf(ImportSkipReason.Passkey))
    }

    @Test
    fun `a broken proton pass passkey does not take the others with it`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.ProtonPass,
            text = protonExport(
                "Site" to listOf(
                    protonPasskey(protonPasskeyContent(kty = "OKP")),
                    protonPasskey(protonPasskeyContent()),
                ),
            ),
        )
        assertEquals(1, plan.items.single().request.fido2Credentials.size)
        assertEquals(mapOf("Site" to 1), plan.skips.titlesOf(ImportSkipReason.Passkey))
    }
}

/**
 * The P-256 key of RFC 6979 A.2.5.
 */
private val P256_D = "c9afa9d845ba75166b5c215767b1d6934e50c3db36e89b127b8a622b120f6721".hexToByteArray()
private val P256_X = "60fed4ba255a9d31c961eb74c6356d68c049b8923b61fa6ce669622e60f29fb6".hexToByteArray()
private val P256_Y = "7903fe1008b8bc99a41ae9e95628bc64f2f1b20c2d7e9f5177a3c294d4462299".hexToByteArray()

private val CREDENTIAL_ID = ByteArray(16) { it.toByte() }
private val USER_HANDLE = "user-1".encodeToByteArray()

private fun ByteArray.toIntList(): List<Int> = map { it.toInt() and 0xff }

/** A serde enum, as rmp_serde writes one: `{"t": variant, "c": value}`. */
private fun tagged(t: String, c: Any?): Map<String, Any?> = mapOf("t" to t, "c" to c)

/**
 * The `content` of a Proton Pass passkey: a `ProtonPassKey` in the layout
 * `rmp_serde::to_vec_named` gives it, wrapped in a `SerializedPassKey`.
 */
private fun protonPasskeyContent(
    kty: String = "EC2",
    d: ByteArray = P256_D,
    x: ByteArray = P256_X,
    y: ByteArray = P256_Y,
    userHandle: List<Int>? = USER_HANDLE.toIntList(),
    counter: Long? = null,
    extensions: Map<String, Any?>? = null,
    version: Int = 1,
): String {
    val key = mapOf(
        "kty" to tagged("assign", kty),
        "kid" to emptyList<Int>(),
        "alg" to tagged("assign", "ES256"),
        "kops" to emptyList<Any>(),
        "biv" to emptyList<Int>(),
        "par" to listOf(
            // The curve is an i128, which serde writes as its 16 bytes.
            listOf(tagged("int", -1), tagged("int", mapOf("inner" to listOf(1) + List(15) { 0 }))),
            listOf(tagged("int", -2), tagged("bytes", x.toIntList())),
            listOf(tagged("int", -3), tagged("bytes", y.toIntList())),
            listOf(tagged("int", -4), tagged("bytes", d.toIntList())),
        ),
    )
    val passkey = buildMap {
        put("key", key)
        put("cid", CREDENTIAL_ID.toIntList())
        put("rid", "webauthn.io")
        put("uhd", userHandle)
        put("cnt", counter)
        if (extensions != null) {
            put("ext", extensions)
        }
        put("udn", "Alice")
        put("un", "alice")
    }
    return protonPasskeyEnvelope(MessagePackCodec.encodeDynamic(passkey), version)
}

private fun protonPasskeyEnvelope(
    content: ByteArray,
    version: Int = 1,
): String {
    val envelope = mapOf(
        "c" to content.toIntList(),
        "v" to version,
    )
    return Base64.encode(MessagePackCodec.encodeDynamic(envelope))
}

private fun protonPasskey(
    content: String?,
    userHandle: String = Base64.encode(USER_HANDLE),
    userId: String = Base64.encode(USER_HANDLE),
): JsonObject = buildJsonObject {
    put("keyId", "AAECAwQFBgcICQoLDA0ODw")
    content?.let { put("content", it) }
    put("domain", "webauthn.io")
    put("rpId", "webauthn.io")
    put("rpName", "WebAuthn.io")
    put("userName", "alice")
    put("userDisplayName", "Alice")
    put("userId", userId)
    put("createTime", 1_714_982_805)
    put("note", "")
    put("credentialId", Base64.encode(CREDENTIAL_ID))
    put("userHandle", userHandle)
}

private fun protonExport(
    vararg logins: Pair<String, List<JsonObject>>,
): String {
    val items = logins.map { (title, passkeys) ->
        buildJsonObject {
            put("state", 1)
            put(
                "data",
                buildJsonObject {
                    put("type", "login")
                    put("metadata", buildJsonObject { put("name", title) })
                    put(
                        "content",
                        buildJsonObject {
                            put("itemUsername", "alice")
                            put("passkeys", JsonArray(passkeys))
                        },
                    )
                },
            )
        }
    }
    return buildJsonObject {
        put(
            "vaults",
            buildJsonObject {
                put(
                    "v",
                    buildJsonObject {
                        put("name", JsonPrimitive("Personal"))
                        put("items", JsonArray(items))
                    },
                )
            },
        )
    }.toString()
}
