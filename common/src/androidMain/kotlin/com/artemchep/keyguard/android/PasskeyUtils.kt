package com.artemchep.keyguard.android

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.provider.CallingAppInfo
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.gpmprivapps.PrivilegedAppsService
import com.artemchep.keyguard.common.usecase.impl.isSubdomain
import com.artemchep.keyguard.common.service.passkey.entity.CreatePasskeyAttestation
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.nio.ByteBuffer
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale
import kotlin.experimental.or

class PasskeyUtils(
    private val cryptoService: CryptoGenerator,
    private val privilegedAppsService: PrivilegedAppsService,
    private val httpClient: HttpClient,
) {
    companion object {
        // https://developers.google.com/identity/smartlock-passwords/android/associate-apps-and-sites
        private const val PERMISSION_GET_LOGIN_CREDS = "delegate_permission/common.get_login_creds"

        /**
         * The minimum time the 'passkey is doing work' screen should
         * be shown. This is needed just to make to user interface less
         * junky.
         */
        private const val PASSKEY_PROCESSING_MIN_TIME_MS = 800L

        suspend fun <T> withProcessingMinTime(
            block: suspend () -> T,
        ): T = coroutineScope {
            val artificialDelayDeferred = async {
                delay(PASSKEY_PROCESSING_MIN_TIME_MS)
            }
            val result = block()
            artificialDelayDeferred.await()
            result
        }
    }

    // We use the same AAGUID as Bitwarden does:
    // d548826e-79b4-db40-a3d8-11116f7e8349
    private val bitwardenAaguid = byteArrayOf(
        0xd5.toByte(),
        0x48.toByte(),
        0x82.toByte(),
        0x6e.toByte(),
        0x79.toByte(),
        0xb4.toByte(),
        0xdb.toByte(),
        0x40.toByte(),
        0xa3.toByte(),
        0xd8.toByte(),
        0x11.toByte(),
        0x11.toByte(),
        0x6f.toByte(),
        0x7e.toByte(),
        0x83.toByte(),
        0x49.toByte(),
    )

    data class CallingAppCertificate(
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CallingAppCertificate

            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            return data.contentHashCode()
        }

        /**
         * Creates an origin from the app signing certificate.
         */
        fun toOrigin(
            cryptoService: CryptoGenerator,
        ): String {
            // FIXME: The documentation also states that the apk-key-hash must be in
            //  the SHA1 format instead, which is fucking great as in reality it
            //  should be in SHA256.
            val certHash = cryptoService.hashSha256(data)
            val certB64 = PasskeyBase64.encodeToString(certHash)
            return "android:apk-key-hash:$certB64"
        }
    }

    sealed interface RpValidation {
        data object Valid : RpValidation
        data object Invalid : RpValidation
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        cryptoService = directDI.instance(),
        privilegedAppsService = directDI.instance(),
        httpClient = directDI.instance(tag = "curl"),
    )

    @RequiresApi(Build.VERSION_CODES.P)
    suspend fun callingAppOrigin(
        appInfo: CallingAppInfo,
    ): String {
        val privilegedAllowList = privilegedAppsService
            .get()
            .bind()
        val origin = kotlin.runCatching {
            appInfo.getOrigin(privilegedAllowList)
        }
            .getOrElse { e ->
                if (!appInfo.isOriginPopulated()) {
                    throw e
                }

                val msg = "The calling app '${appInfo.packageName}' is not on the privileged list and cannot " +
                        "request authentication on behalf of the other app. You can submit a request on GitHub for adding the app " +
                        "to the privileged list, if you think that the app is a trustworthy known browser."
                throw IllegalStateException(msg, e)
            }
            ?: kotlin.run {
                val cert = callingAppCertificate(appInfo)
                cert.toOrigin(cryptoService)
            }
        return origin
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun callingAppCertificate(
        appInfo: CallingAppInfo,
    ): CallingAppCertificate {
        val signatureBytes = appInfo.signingInfo.apkContentsSigners[0].toByteArray()

        val certFactory = CertificateFactory.getInstance("X509")
        val cert = signatureBytes
            .inputStream()
            .use {
                certFactory.generateCertificate(it) as X509Certificate
            }

        val certBytes = cert.encoded
        return CallingAppCertificate(certBytes)
    }

    /**
     * Validates that the origin is known to the relaying party and
     * we can give a response to this request.
     */
    suspend fun requireRpMatchesOrigin(
        rpId: String,
        origin: String,
        packageName: String,
    ): Unit = runCatching {
        when {
            origin.startsWith("https:", ignoreCase = true) ->
                requireRpMatchesOriginViaHttps(rpId, origin)

            origin.startsWith("android:", ignoreCase = true) ->
                requireRpMatchesOriginViaAndroid(rpId, origin, packageName)

            else -> throw IllegalStateException("Request origin has an unknown scheme.")
        }
    }.getOrElse {
        val detailMessage = it.localizedMessage
            ?: it.message
        val fullMessage =
            "Failed to verify the relation with the Relaying Party. $detailMessage"
        throw IllegalStateException(fullMessage)
    }

    private suspend fun requireRpMatchesOriginViaHttps(
        rpId: String,
        origin: String,
    ) {
        val originUrl = Url(origin)
        val valid = isSubdomain(
            domain = rpId,
            request = originUrl.host,
        )
        require(valid) {
            "Request origin '${originUrl.host}' is not associated with the relying party!"
        }
    }

    private suspend fun requireRpMatchesOriginViaAndroid(
        rpId: String,
        origin: String,
        packageName: String,
    ) {
        // The origin should be in the format:
        // android:apk-key-hash:$certB64
        val certB64 = origin.substringAfter("android:apk-key-hash:")
        require(certB64 != origin) {
            "Request origin has unknown android format."
        }

        val certBytes = PasskeyBase64.decode(certB64)

        // Check if the app target matches the signature.
        fun validateAndroidAppTarget(t: AndroidAppTarget): Boolean {
            return t.fingerprints
                .any { fingerprint ->
                    fingerprint.contentEquals(certBytes)
                }
        }

        val androidAppGetLoginCredsRelation = AndroidAppRelation(PERMISSION_GET_LOGIN_CREDS)
        val androidAppPackageName = AndroidAppPackageName(packageName)
        // Download certificates from the website and compare it with
        // the certificate we have locally.
        val targets = obtainAllowedAndroidAppTargets(rpId = rpId)
        val target = targets[androidAppGetLoginCredsRelation]
            ?.get(androidAppPackageName)
        requireNotNull(target) {
            val details = targets
                .entries
                .mapNotNull { (relation, appTarget) ->
                    // Check if the target matches the app, if so
                    // also check if the signature matches the app.
                    val t = appTarget[androidAppPackageName]
                        ?: return@mapNotNull null
                    val valid = validateAndroidAppTarget(t)

                    val emoji = if (valid) "✅" else "\uD83D\uDEAB"
                    "$emoji ${relation.relation};"
                }
            if (details.isNotEmpty()) {
                val list = details.joinToString("\n")
                return@requireNotNull "App package name is associated with the " +
                        "relying party, however none of the specified relations allow sharing " +
                        "credentials:\n$list\n\nThe $PERMISSION_GET_LOGIN_CREDS is " +
                        "missing."
            }

            "App package name is not associated with the " +
                    "relying party!"
        }

        val valid = target.fingerprints.any {
            it.contentEquals(certBytes)
        }
        require(valid) {
            "App signature is not associated with the " +
                    "relying party!"
        }
    }

    @JvmInline
    private value class AndroidAppRelation(
        val relation: String,
    )

    @JvmInline
    private value class AndroidAppPackageName(
        val packageName: String,
    )

    private class AndroidAppTarget(
        val fingerprints: List<ByteArray>,
    )

    @OptIn(ExperimentalStdlibApi::class)
    private suspend fun obtainAllowedAndroidAppTargets(
        rpId: String,
    ): Map<AndroidAppRelation, Map<AndroidAppPackageName, AndroidAppTarget>> {
        val url = "https://$rpId/.well-known/assetlinks.json"
        val response = httpClient.get(url)
        require(response.status.isSuccess()) {
            "Failed to get asset links from relying party, " +
                    "error code ${response.status.value}."
        }
        val result = response
            .body<JsonElement>()

        // Collect all the android app targets that
        // allow a user to login with.
        val collector = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()
        val list = result as JsonArray
        list.forEach {
            // Ignore the errors, continue to the
            // next item. I have no idea what the exact
            // schema of the 'assetlinks.json' and how
            // it changes in the future.
            runCatching {
                val node = it as JsonObject
                val relationJsonArray = node["relation"] as JsonArray
                val targetJsonObject = node["target"] as JsonObject

                // We are only checking allowed android apps,
                // so check for the namespace first.
                val namespace = targetJsonObject["namespace"]?.jsonPrimitive?.content
                if (namespace != "android_app") {
                    return@forEach
                }
                // Get all relations
                val relations = relationJsonArray
                    .asSequence()
                    .mapNotNull { el ->
                        val elContent = runCatching {
                            el.jsonPrimitive.content
                        }.getOrNull()
                        elContent
                    }
                    .toSet()
                if (relations.isEmpty()) {
                    return@forEach
                }

                val packageName = targetJsonObject["package_name"]?.jsonPrimitive?.content
                requireNotNull(packageName)
                val fingerprints = kotlin.run {
                    val arr = targetJsonObject["sha256_cert_fingerprints"] as JsonArray
                    arr.map { el -> el.jsonPrimitive.content }
                }

                // Save the fingerprints separately for
                // every relation.
                relations.forEach { relation ->
                    val relationGroup = collector.getOrPut(relation) { mutableMapOf() }
                    val packageNameGroup = relationGroup.getOrPut(packageName) { mutableSetOf() }
                    packageNameGroup.addAll(fingerprints)
                }
            }
        }
        return collector
            .entries
            .associate { (relation, appTarget) ->
                val androidAppRelation = AndroidAppRelation(relation)
                val androidAppTarget = appTarget
                    .entries
                    .associate { (packageName, fingerprints) ->
                        val androidAppPackageName = AndroidAppPackageName(packageName)
                        val androidAppTarget = AndroidAppTarget(
                            fingerprints = fingerprints
                                .map { fingerprintHex ->
                                    val rawFingerprintHex = fingerprintHex
                                        .lowercase(Locale.ENGLISH)
                                        .replace(":", "")
                                    rawFingerprintHex.hexToByteArray()
                                },
                        )
                        androidAppPackageName to androidAppTarget
                    }
                androidAppRelation to androidAppTarget
            }
    }

    fun generateCredentialId() = cryptoService.uuid()

    // See:
    // https://github.com/1Password/passkey-rs/blob/90c1c282649eceeb7cbe771bb8ce17b1b8463c60/passkey-client/src/lib.rs#L407
    // https://github.com/kanidm/webauthn-rs/blame/25bc74ac0dc4280bf67ed3ff53fdf804dbb142c2/webauthn-rs-core/src/core.rs#L866
    fun userVerification(
        mode: String?,
        userVerified: Boolean,
    ): Boolean = when (mode ?: "preferred") {
        "required" -> userVerified
        "preferred" -> true
        "discouraged" -> false
        // should never happen
        else -> userVerified
    }

    fun authData(
        rpId: String,
        counter: Int,
        credentialId: ByteArray,
        credentialPublicKey: ByteArray?,
        attestation: CreatePasskeyAttestation? = null,
        userVerification: Boolean,
        userPresence: Boolean,
    ): ByteArray {
        val out = mutableListOf<ByteArray>()

        // 1. Replay party ID hash.
        val rpIdHash = run {
            val data = rpId.toByteArray()
            cryptoService.hashSha256(data)
        }
        out += rpIdHash

        // 2. Auth data flags.
        out += authDataFlags(
            extensionData = false,
            attestationData = credentialPublicKey != null,
            backupState = true,
            backupEligibility = true,
            userVerification = userVerification,
            userPresence = userPresence,
        ).let {
            byteArrayOf(it)
        }

        // 3. Counter. We have 4 bytes allocated
        // to it.
        out += ByteBuffer.allocate(Int.SIZE_BYTES)
            .putInt(counter)
            .array()

        // 4. Attestation data:
        //  a) aaguid (16)
        //  b) credential id length (2)
        //  c) credential id
        //  d) credential public key
        if (credentialPublicKey != null) {
            val aaguid = when (attestation) {
                // Convey the authenticator's AAGUID and attestation statement,
                // unaltered, to the Relying Party.
                CreatePasskeyAttestation.DIRECT,
                CreatePasskeyAttestation.ENTERPRISE,
                -> bitwardenAaguid
                // The client MAY replace the AAGUID and attestation statement with a more
                // privacy-friendly and/or more easily verifiable version of the same data
                // (for example, by employing an Anonymization CA).
                CreatePasskeyAttestation.INDIRECT -> {
                    val data = bitwardenAaguid + credentialId
                    val hash = cryptoService.hashMd5(data)
                    if (hash.size == 16) hash else hash.sliceArray(0..15)
                }
                // Replace the AAGUID in the attested
                // credential data with 16 zero bytes.
                null,
                CreatePasskeyAttestation.NONE,
                -> ByteArray(16)
            }
            out += aaguid

            val credIdLen = byteArrayOf(
                (credentialId.size shr 8).toByte(),
                credentialId.size.toByte(),
            )
            out += credIdLen
            out += credentialId
            out += credentialPublicKey
        }

        return out.fold(
            initial = run {
                val size = out.sumOf { it.size }
                ByteBuffer.allocate(size)
            },
        ) { buffer, array ->
            buffer.put(array)
        }.array()
    }

    private fun authDataFlags(
        extensionData: Boolean,
        attestationData: Boolean,
        backupState: Boolean,
        backupEligibility: Boolean,
        userVerification: Boolean,
        userPresence: Boolean,
    ): Byte {
        var flags: Byte = 0
        if (extensionData) {
            flags = flags or 0b1000000
        }
        if (attestationData) {
            flags = flags or 0b01000000
        }
        if (backupState) {
            flags = flags or 0b00010000
        }
        if (backupEligibility) {
            flags = flags or 0b00001000
        }
        if (userVerification) {
            flags = flags or 0b00000100
        }
        if (userPresence) {
            flags = flags or 0b00000001
        }
        return flags
    }
}
