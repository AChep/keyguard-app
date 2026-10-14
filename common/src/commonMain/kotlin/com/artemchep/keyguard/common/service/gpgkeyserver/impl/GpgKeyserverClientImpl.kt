package com.artemchep.keyguard.common.service.gpgkeyserver.impl

import com.artemchep.keyguard.common.exception.GpgKeyserverFingerprintMismatchException
import com.artemchep.keyguard.common.exception.GpgKeyserverShortKeyIdException
import com.artemchep.keyguard.common.exception.GpgKeyserverUnsupportedKeyVersionException
import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DGpgKeyserverResult
import com.artemchep.keyguard.common.model.DGpgKeyserverSubKey
import com.artemchep.keyguard.common.model.DGpgKeyserverUploadResult
import com.artemchep.keyguard.common.model.GpgKeyserverConfig
import com.artemchep.keyguard.common.model.SearchGpgPublicKeyRequest
import com.artemchep.keyguard.common.service.crypto.GPG_LONG_KEY_ID_LENGTH
import com.artemchep.keyguard.common.service.crypto.GPG_V6_FINGERPRINT_LENGTH
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseError
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.crypto.extractGpgUserIdEmail
import com.artemchep.keyguard.common.service.crypto.gpgAlgorithmName
import com.artemchep.keyguard.common.service.crypto.gpgKeyIdFromFingerprintOrNull
import com.artemchep.keyguard.common.service.crypto.parseGpgFingerprintOrNull
import com.artemchep.keyguard.common.service.crypto.parseGpgKeyIdOrNull
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverClient
import com.artemchep.keyguard.provider.bitwarden.api.builder.ensureSuffix
import com.artemchep.keyguard.provider.bitwarden.api.builder.routeAttribute
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.options
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.content.TextContent
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.isSuccess
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class GpgKeyserverClientImpl(
    private val httpClient: HttpClient,
    private val parser: GpgPublicKeyParser,
    private val json: Json = Json {
        ignoreUnknownKeys = true
    },
) : GpgKeyserverClient {
    companion object {
        const val ROUTE_VKS_BY_FINGERPRINT = "gpg-keyserver-vks-by-fingerprint"
        const val ROUTE_VKS_BY_KEY_ID = "gpg-keyserver-vks-by-key-id"
        const val ROUTE_VKS_BY_EMAIL = "gpg-keyserver-vks-by-email"
        const val ROUTE_HKP_INDEX = "gpg-keyserver-hkp-index"
        const val ROUTE_HKP_GET = "gpg-keyserver-hkp-get"
        const val ROUTE_HKP_V2_BY_FINGERPRINT = "gpg-keyserver-hkp-v2-by-fingerprint"
        const val ROUTE_HKP_V2_OPTIONS = "gpg-keyserver-hkp-v2-options"
        const val ROUTE_VKS_UPLOAD = "gpg-keyserver-vks-upload"
        const val ROUTE_VKS_REQUEST_VERIFY = "gpg-keyserver-vks-request-verify"
        const val ROUTE_HKP_ADD = "gpg-keyserver-hkp-add"

        private val emailRegex = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

        // keys.openpgp.org rate-limits its by-email VKS endpoint; keep at least
        // this interval between consecutive by-email lookups. Lives on the
        // (global-singleton) client so the throttle applies to every caller and
        // survives vault unlocks, not just a single verification session.
        private const val VKS_BY_EMAIL_THROTTLE_MILLIS = 65_000L

        // The line length GnuPG uses for ASCII armor.
        private const val ARMOR_LINE_LENGTH = 64
    }

    private val vksByEmailGate = GpgKeyserverVksByEmailGate<List<DGpgKeyserverResult>>(
        intervalMillis = VKS_BY_EMAIL_THROTTLE_MILLIS,
    )

    override fun search(
        request: SearchGpgPublicKeyRequest,
        config: GpgKeyserverConfig,
    ): IO<List<DGpgKeyserverResult>> = ioEffect(Dispatchers.IO) {
        // An empty query, or one that does not fit the requested mode,
        // has nothing to look up.
        val query = request.toQueryOrNull()
            ?: return@ioEffect emptyList()

        val effectiveConfig = request.keyserverConfig
            ?.takeIf { it.url.trim().isNotEmpty() }
            ?.let { it.copy(url = it.url.trim()) }
            ?: request.keyserver
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { config.copy(url = it) }
            ?: config
        lookup(
            query = query,
            config = effectiveConfig,
        )
            .distinctBy { it.fingerprint.normalizeGpgFingerprint() }
    }

    override fun getByFingerprint(
        fingerprint: String,
        config: GpgKeyserverConfig,
    ): IO<DGpgKeyserverResult?> = ioEffect(Dispatchers.IO) {
        // A stored value that is not a fingerprint must fail: callers
        // persist a `null` result as "not found".
        val query = fingerprint.parseGpgFingerprintOrNull()
            ?.let(Query::Fingerprint)
            ?: throw IllegalArgumentException("The GPG key fingerprint is not a V4 or V6 fingerprint.")
        lookup(
            query = query,
            config = config,
        ).firstOrNull()
    }

    override fun canServeSearch(
        request: SearchGpgPublicKeyRequest,
        config: GpgKeyserverConfig,
    ): Boolean {
        val query = request.toQueryOrNull()
            // Nothing to serve; search() returns an empty result.
            ?: return true
        val effectiveConfig = request.keyserverConfig ?: config
        return when (effectiveConfig.protocol) {
            // HKP exposes a free-text index endpoint, so it can serve any mode.
            GpgKeyserverConfig.Protocol.HKP -> true
            // VKS only offers by-fingerprint / by-key-id / by-email lookups.
            // A lookup that no keyserver serves, such as a short key ID,
            // fails in search() instead of being re-routed.
            GpgKeyserverConfig.Protocol.VKS -> query !is Query.Text
        }
    }

    override fun getByEmail(
        email: String,
        config: GpgKeyserverConfig,
    ): IO<List<DGpgKeyserverResult>> = ioEffect(Dispatchers.IO) {
        val query = email.trim()
        if (query.isEmpty()) {
            throw IllegalArgumentException("The GPG key e-mail is empty.")
        }

        when (config.protocol) {
            GpgKeyserverConfig.Protocol.VKS -> vksByEmailGate.execute(
                key = vksByEmailCacheKey(
                    email = query,
                    config = config,
                ),
            ) {
                searchVks(
                    query = Query.Email(query),
                    config = config,
                )
            }

            GpgKeyserverConfig.Protocol.HKP -> getHkpIndex(
                query = query,
                config = config,
            )
        }
            .distinctBy { it.fingerprint.normalizeGpgFingerprint() }
    }

    override fun upload(
        publicKeyArmored: String,
        config: GpgKeyserverConfig,
    ): IO<DGpgKeyserverUploadResult> = ioEffect(Dispatchers.IO) {
        val keytext = publicKeyArmored.trim()
        if (keytext.isEmpty()) {
            throw IllegalArgumentException("The public GPG key is empty.")
        }

        when (config.protocol) {
            GpgKeyserverConfig.Protocol.VKS -> postVks(
                path = "upload",
                route = ROUTE_VKS_UPLOAD,
                body = json.encodeToString(VksUploadRequest(keytext = keytext)),
                config = config,
            )

            GpgKeyserverConfig.Protocol.HKP -> {
                uploadHkp(
                    keytext = keytext,
                    config = config,
                )
                DGpgKeyserverUploadResult()
            }
        }
    }

    override fun requestVerify(
        token: String,
        addresses: Collection<String>,
        config: GpgKeyserverConfig,
    ): IO<DGpgKeyserverUploadResult> = ioEffect(Dispatchers.IO) {
        if (token.isBlank()) {
            throw IllegalArgumentException("The keyserver upload token is empty.")
        }
        val normalizedAddresses = addresses
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (normalizedAddresses.isEmpty()) {
            throw IllegalArgumentException("No e-mail addresses to verify.")
        }

        when (config.protocol) {
            GpgKeyserverConfig.Protocol.VKS -> postVks(
                path = "request-verify",
                route = ROUTE_VKS_REQUEST_VERIFY,
                body = json.encodeToString(
                    VksRequestVerifyRequest(
                        token = token,
                        addresses = normalizedAddresses,
                    ),
                ),
                config = config,
            )

            GpgKeyserverConfig.Protocol.HKP -> throw UnsupportedOperationException(
                "HKP keyservers do not verify e-mail addresses.",
            )
        }
    }

    private fun vksByEmailCacheKey(
        email: String,
        config: GpgKeyserverConfig,
    ): String = config.url + "|" + email.lowercase()

    private suspend fun lookup(
        query: Query,
        config: GpgKeyserverConfig,
    ): List<DGpgKeyserverResult> {
        query.requireSupported(config)
        val results = when (config.protocol) {
            GpgKeyserverConfig.Protocol.VKS -> searchVks(
                query = query,
                config = config,
            )

            GpgKeyserverConfig.Protocol.HKP -> searchHkp(
                query = query,
                config = config,
            )
        }
        return if (query is Query.Fingerprint) {
            results.requireGpgFingerprint(query.fingerprint)
        } else {
            results
        }
    }

    /**
     * Fails before any request for a lookup that the keyserver cannot
     * serve: its answer would look the same as a missing key.
     */
    private fun Query.requireSupported(config: GpgKeyserverConfig) {
        if (this is Query.KeyId && keyId.length != GPG_LONG_KEY_ID_LENGTH) {
            throw GpgKeyserverShortKeyIdException()
        }
        // VKS keyservers store V4 keys only, see
        // https://gitlab.com/keys.openpgp.org/hagrid/-/work_items/163
        if (
            this is Query.Fingerprint &&
            fingerprint.length == GPG_V6_FINGERPRINT_LENGTH &&
            config.protocol == GpgKeyserverConfig.Protocol.VKS
        ) {
            throw GpgKeyserverUnsupportedKeyVersionException()
        }
    }

    private fun Query.toVksLookupOrNull(): VksLookup? = when (this) {
        is Query.Fingerprint -> VksLookup(
            path = "by-fingerprint",
            value = fingerprint,
            route = ROUTE_VKS_BY_FINGERPRINT,
        )

        is Query.KeyId -> VksLookup(
            path = "by-keyid",
            value = keyId,
            route = ROUTE_VKS_BY_KEY_ID,
        )

        is Query.Email -> VksLookup(
            path = "by-email",
            value = email,
            route = ROUTE_VKS_BY_EMAIL,
        )

        is Query.Text -> null
    }

    private suspend fun searchVks(
        query: Query,
        config: GpgKeyserverConfig,
    ): List<DGpgKeyserverResult> {
        val lookup = query.toVksLookupOrNull()
        if (lookup == null) {
            // The VKS protocol has no free-text search endpoint, so this
            // transport cannot serve a free-text query. Report it honestly as
            // empty instead of silently re-routing the user's search term to a
            // different keyserver; SearchGpgPublicKeyImpl owns that fallback.
            return emptyList()
        }

        val url = buildUrl(config.url) {
            appendPathSegments(
                "vks",
                "v1",
                lookup.path,
                lookup.value,
            )
        }
        val response = httpClient.get(url) {
            attributes.put(routeAttribute, lookup.route)
        }
        if (response.status == HttpStatusCode.NotFound) {
            return emptyList()
        }
        response.requireSuccess()
        val armored = response.bodyAsText()
        return parseArmored(
            armored = armored,
            sourceConfig = config,
        )
    }

    /**
     * Upload and request-verify accept only a JSON [body] and share the same
     * response shape, see https://keys.openpgp.org/about/api
     */
    private suspend fun postVks(
        path: String,
        route: String,
        body: String,
        config: GpgKeyserverConfig,
    ): DGpgKeyserverUploadResult {
        val url = buildUrl(config.url) {
            appendPathSegments("vks", "v1", path)
        }
        val response = httpClient.post(url) {
            attributes.put(routeAttribute, route)
            setBody(
                TextContent(
                    text = body,
                    contentType = ContentType.Application.Json,
                ),
            )
        }
        response.requireSuccess()

        val text = response.bodyAsText()
        val result = try {
            json.decodeFromString(
                VksUploadResponse.serializer(),
                text,
            )
        } catch (e: SerializationException) {
            throw IllegalStateException("Keyserver returned a malformed upload response.", e)
        }
        val fingerprint = result.fingerprint.normalizeGpgFingerprint()
        check(fingerprint.isNotEmpty()) {
            "Keyserver did not confirm the uploaded GPG key fingerprint."
        }
        return DGpgKeyserverUploadResult(
            fingerprint = fingerprint,
            // Unknown statuses are dropped rather than failing the upload.
            emailStatus = result.status
                .mapNotNull { (email, status) ->
                    DGpgKeyserverUploadResult.EmailStatus.entries
                        .firstOrNull { it.name.equals(status, ignoreCase = true) }
                        ?.let { email to it }
                }
                .toMap(),
            token = result.token?.takeIf { it.isNotBlank() },
        )
    }

    private suspend fun uploadHkp(
        keytext: String,
        config: GpgKeyserverConfig,
    ) {
        val url = buildUrl(config.url) {
            appendPathSegments("pks", "add")
        }
        val response = httpClient.post(url) {
            attributes.put(routeAttribute, ROUTE_HKP_ADD)
            setBody(keytextForm(keytext))
        }
        response.requireSuccess()
    }

    private suspend fun searchHkp(
        query: Query,
        config: GpgKeyserverConfig,
    ): List<DGpgKeyserverResult> = when (query) {
        is Query.Fingerprint -> getHkpByFingerprint(
            fingerprint = query.fingerprint,
            config = config,
        )

        // HKP keyservers match a key ID only with a lower-case `0x`
        // prefix; without it the query is a free-text search.
        is Query.KeyId -> getHkpIndex(
            query = "0x${query.keyId}",
            config = config,
        )

        is Query.Email -> getHkpIndex(
            query = query.email,
            config = config,
        )

        is Query.Text -> getHkpIndex(
            query = query.text,
            config = config,
        )
    }

    /** Looks up a normalized [fingerprint]. */
    private suspend fun getHkpByFingerprint(
        fingerprint: String,
        config: GpgKeyserverConfig,
    ): List<DGpgKeyserverResult> {
        // The legacy API of newer keyservers hides V6 keys to protect
        // older clients, and older keyservers cannot store them.
        if (fingerprint.length == GPG_V6_FINGERPRINT_LENGTH) {
            return getHkpV2ByFingerprint(
                fingerprint = fingerprint,
                config = config,
            )
        }

        val url = buildUrl(config.url) {
            appendPathSegments("pks", "lookup")
        }
        val response = httpClient.get(url) {
            attributes.put(routeAttribute, ROUTE_HKP_GET)
            parameter("op", "get")
            parameter("options", "mr")
            parameter("search", "0x$fingerprint")
        }
        if (response.status == HttpStatusCode.NotFound) {
            return emptyList()
        }
        response.requireSuccess()
        return parseArmored(
            armored = response.bodyAsText(),
            sourceConfig = config,
        )
    }

    /**
     * Looks up a normalized V6 [fingerprint] with the v2 API, see
     * https://datatracker.ietf.org/doc/draft-ietf-openpgp-hkp/ section 5.1.2.
     */
    private suspend fun getHkpV2ByFingerprint(
        fingerprint: String,
        config: GpgKeyserverConfig,
    ): List<DGpgKeyserverResult> {
        val url = buildUrl(config.url) {
            appendPathSegments("pks", "v2", "certs", "by-vfingerprint", "06", fingerprint)
        }
        val response = httpClient.get(url) {
            attributes.put(routeAttribute, ROUTE_HKP_V2_BY_FINGERPRINT)
        }
        // A keyserver without the v2 API answers 404 as well, so only
        // one that has the lookup reports a missing key.
        if (response.status == HttpStatusCode.NotFound && hasHkpV2FingerprintLookup(config)) {
            return emptyList()
        }
        if (
            response.status == HttpStatusCode.NotFound ||
            response.status == HttpStatusCode.NotImplemented
        ) {
            throw GpgKeyserverUnsupportedKeyVersionException()
        }
        response.requireSuccess()
        // The v2 API returns binary certificates.
        return parseArmored(
            armored = response.bodyAsBytes().toPublicKeyArmor(),
            sourceConfig = config,
        )
    }

    /**
     * Whether the keyserver has the v2 fingerprint lookup, see
     * https://datatracker.ietf.org/doc/draft-ietf-openpgp-hkp/ section 5.1.7.
     */
    private suspend fun hasHkpV2FingerprintLookup(
        config: GpgKeyserverConfig,
    ): Boolean {
        val url = buildUrl(config.url) {
            appendPathSegments("pks", "v2", "certs", "by-vfingerprint")
        }
        val response = httpClient.options(url) {
            attributes.put(routeAttribute, ROUTE_HKP_V2_OPTIONS)
        }
        return response.status.isSuccess() ||
            response.headers[HttpHeaders.Allow]
                .orEmpty()
                .split(',')
                .any { it.trim().equals("GET", ignoreCase = true) }
    }

    private suspend fun getHkpIndex(
        query: String,
        config: GpgKeyserverConfig,
    ): List<DGpgKeyserverResult> {
        val url = buildUrl(config.url) {
            appendPathSegments("pks", "lookup")
        }
        val response = httpClient.get(url) {
            attributes.put(routeAttribute, ROUTE_HKP_INDEX)
            parameter("op", "index")
            parameter("options", "mr")
            // Asks keyservers that list key IDs by default for the
            // fingerprints; the others ignore it.
            parameter("fingerprint", "on")
            parameter("search", query)
        }
        if (response.status == HttpStatusCode.NotFound) {
            return emptyList()
        }
        response.requireSuccess()
        return parseHkpMachineReadable(
            text = response.bodyAsText(),
            sourceConfig = config,
        )
    }

    private fun keytextForm(
        keytext: String,
    ) = FormDataContent(
        Parameters.build {
            append("keytext", keytext)
        },
    )

    private fun parseArmored(
        armored: String,
        sourceConfig: GpgKeyserverConfig,
    ): List<DGpgKeyserverResult> =
        when (val result = parser.parse(armored)) {
            is GpgPublicKeyParseResult.Success -> result.keys
                .ifEmpty {
                    throw IllegalStateException("Keyserver returned no OpenPGP public key data.")
                }
                .map { key ->
                    key.toResult(sourceConfig)
                }

            is GpgPublicKeyParseResult.Error -> when (result.reason) {
                GpgPublicKeyParseError.Empty -> throw IllegalStateException(
                    "Keyserver returned no OpenPGP public key data.",
                )

                GpgPublicKeyParseError.UnsupportedKeyVersion -> throw IllegalStateException(
                    "Keyserver returned an unsupported OpenPGP public key version.",
                )

                GpgPublicKeyParseError.Malformed -> throw IllegalStateException(
                    "Keyserver returned malformed OpenPGP public key data.",
                )

                // A keyserver response never carries secret key material, so
                // this reason cannot legitimately occur here.
                GpgPublicKeyParseError.MultipleCertificates -> throw IllegalStateException(
                    "Keyserver returned secret OpenPGP key material.",
                )

                GpgPublicKeyParseError.Unsupported -> throw UnsupportedOperationException(
                    "OpenPGP public key parsing is not supported on this platform.",
                )
            }
        }

    private fun parseHkpMachineReadable(
        text: String,
        sourceConfig: GpgKeyserverConfig,
    ): List<DGpgKeyserverResult> {
        val out = mutableListOf<HkpRecord>()
        var current: HkpRecord? = null

        fun flush() {
            val record = current ?: return
            out += record
            current = null
        }

        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val parts = line.split(':')
                when (parts.firstOrNull()) {
                    "pub" -> {
                        flush()
                        // Either the fingerprint or the key ID of the key.
                        val keyHandle = parts.getOrNull(1)
                        current = HkpRecord(
                            keyId = keyHandle?.parseGpgKeyIdOrNull(),
                            fingerprint = keyHandle?.parseGpgFingerprintOrNull(),
                            algorithm = parts.getOrNull(2)
                                ?.toIntOrNull()
                                ?.let(::gpgAlgorithmName),
                            createdAt = parts.getOrNull(4)
                                ?.toLongOrNull()
                                ?.let(Instant::fromEpochSeconds),
                            expiresAt = parts.getOrNull(5)
                                ?.toLongOrNull()
                                ?.takeIf { it > 0L }
                                ?.let(Instant::fromEpochSeconds),
                            revoked = parts.getOrNull(6)
                                ?.contains('r') == true,
                        )
                    }

                    "fpr" -> {
                        val fingerprint = parts
                            .asSequence()
                            .drop(1)
                            .firstNotNullOfOrNull { it.parseGpgFingerprintOrNull() }
                            ?: return@forEach
                        current = current?.copy(fingerprint = fingerprint)
                    }

                    "uid" -> {
                        val userId = parts.getOrNull(1)
                            ?.decodeURLQueryComponent(plusIsSpace = true)
                            ?.takeIf { it.isNotBlank() }
                            ?: return@forEach
                        current = current?.let { record ->
                            record.copy(userIds = record.userIds + userId)
                        }
                    }
                }
            }
        flush()

        return out
            .mapNotNull { record ->
                // A key ID cannot safely identify the key to download, so
                // a row without a fingerprint is dropped.
                val fingerprint = record.fingerprint
                    ?: return@mapNotNull null
                DGpgKeyserverResult(
                    fingerprint = fingerprint,
                    keyId = record.keyId
                        ?: fingerprint.gpgKeyIdFromFingerprintOrNull(),
                    userIds = record.userIds.distinct(),
                    emails = record.userIds.mapNotNull(::extractGpgUserIdEmail).distinct(),
                    algorithm = record.algorithm,
                    createdAt = record.createdAt,
                    expiresAt = record.expiresAt,
                    revoked = record.revoked,
                    sourceKeyserver = sourceConfig.url,
                    sourceKeyserverConfig = sourceConfig,
                )
            }
    }

    private suspend fun HttpResponse.requireSuccess() {
        if (status.isSuccess()) {
            return
        }
        val message = bodyAsText()
            .takeIf { it.isNotBlank() }
            ?: status.description
        throw HttpException(
            statusCode = status,
            m = message,
            e = null,
            route = call.attributes.getOrNull(routeAttribute),
        )
    }

    private fun GpgPublicKeyInfo.toResult(
        sourceConfig: GpgKeyserverConfig,
    ): DGpgKeyserverResult = DGpgKeyserverResult(
        fingerprint = fingerprint,
        keygrip = keygrip,
        keyId = keyId,
        userIds = userIds,
        emails = emails,
        algorithm = algorithm,
        canSign = canSign,
        canEncrypt = canEncrypt,
        createdAt = createdAt,
        expiresAt = expiresAt,
        revoked = revoked,
        subKeys = subKeys.map { subKey ->
            DGpgKeyserverSubKey(
                fingerprint = subKey.fingerprint,
                keygrip = subKey.keygrip,
                keyId = subKey.keyId,
                algorithm = subKey.algorithm,
                canSign = subKey.canSign,
                canEncrypt = subKey.canEncrypt,
                revoked = subKey.revoked,
                expiresAt = subKey.expiresAt,
            )
        },
        publicKeyArmored = publicKeyArmored,
        sourceKeyserver = sourceConfig.url,
        sourceKeyserverConfig = sourceConfig,
    )

    /**
     * Keeps the keys whose primary key has the [fingerprint]. A key that
     * holds it only as a subkey is someone else's: anyone can bind a copy
     * of a public key to their own key as a subkey.
     *
     * The fingerprint may come from a scanned QR code, so the user cannot
     * spot a keyserver that answers with another key.
     */
    private fun List<DGpgKeyserverResult>.requireGpgFingerprint(
        fingerprint: String,
    ): List<DGpgKeyserverResult> {
        val matches = filter { it.fingerprint.normalizeGpgFingerprint() == fingerprint }
        if (matches.isEmpty() && isNotEmpty()) {
            throw GpgKeyserverFingerprintMismatchException()
        }
        return matches
    }

    private fun SearchGpgPublicKeyRequest.toQueryOrNull(): Query? {
        val text = query.trim()
        if (text.isEmpty()) {
            return null
        }
        return when (mode) {
            SearchGpgPublicKeyRequest.Mode.AUTO -> text.parseGpgFingerprintOrNull()?.let(Query::Fingerprint)
                ?: text.parseGpgKeyIdOrNull()?.let(Query::KeyId)
                ?: if (emailRegex.matches(text)) Query.Email(text) else Query.Text(text)

            SearchGpgPublicKeyRequest.Mode.FINGERPRINT -> text.parseGpgFingerprintOrNull()?.let(Query::Fingerprint)
            SearchGpgPublicKeyRequest.Mode.KEY_ID -> text.parseGpgKeyIdOrNull()?.let(Query::KeyId)
            SearchGpgPublicKeyRequest.Mode.EMAIL -> Query.Email(text)
            SearchGpgPublicKeyRequest.Mode.TEXT -> Query.Text(text)
        }
    }

    // The CRC24 footer is optional, see RFC 9580 section 6.1.
    private fun ByteArray.toPublicKeyArmor(): String = buildString {
        appendLine("-----BEGIN PGP PUBLIC KEY BLOCK-----")
        appendLine()
        Base64.Default.encode(this@toPublicKeyArmor)
            .chunked(ARMOR_LINE_LENGTH)
            .forEach(::appendLine)
        append("-----END PGP PUBLIC KEY BLOCK-----")
    }

    private fun buildUrl(
        baseUrl: String,
        block: URLBuilder.() -> Unit,
    ): String = URLBuilder(Url(baseUrl.ensureSuffix("/")))
        .apply(block)
        .buildString()

    @Serializable
    private data class VksUploadRequest(
        @SerialName("keytext")
        val keytext: String,
    )

    @Serializable
    private data class VksRequestVerifyRequest(
        @SerialName("token")
        val token: String,
        @SerialName("addresses")
        val addresses: List<String>,
    )

    @Serializable
    private data class VksUploadResponse(
        @SerialName("key_fpr")
        val fingerprint: String = "",
        @SerialName("status")
        val status: Map<String, String> = emptyMap(),
        @SerialName("token")
        val token: String? = null,
    )

    /** A search query, resolved and normalized once. */
    private sealed interface Query {
        /** A normalized V4 or V6 fingerprint. */
        data class Fingerprint(val fingerprint: String) : Query

        /** A normalized long or short key ID. */
        data class KeyId(val keyId: String) : Query

        data class Email(val email: String) : Query

        data class Text(val text: String) : Query
    }

    private data class VksLookup(
        val path: String,
        val value: String,
        val route: String,
    )

    private data class HkpRecord(
        val keyId: String?,
        val fingerprint: String?,
        val algorithm: String?,
        val createdAt: Instant?,
        val expiresAt: Instant?,
        val revoked: Boolean,
        val userIds: List<String> = emptyList(),
    )
}
