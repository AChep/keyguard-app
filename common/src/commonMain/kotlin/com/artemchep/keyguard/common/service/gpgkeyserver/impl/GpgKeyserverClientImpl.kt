package com.artemchep.keyguard.common.service.gpgkeyserver.impl

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DGpgKeyserverResult
import com.artemchep.keyguard.common.model.DGpgKeyserverSubKey
import com.artemchep.keyguard.common.model.GpgKeyserverConfig
import com.artemchep.keyguard.common.model.SearchGpgPublicKeyRequest
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseError
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.crypto.extractGpgUserIdEmail
import com.artemchep.keyguard.common.service.crypto.gpgAlgorithmName
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverClient
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.provider.bitwarden.api.builder.ensureSuffix
import com.artemchep.keyguard.provider.bitwarden.api.builder.routeAttribute
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Clock
import kotlin.time.Instant

class GpgKeyserverClientImpl(
    private val httpClient: HttpClient,
    private val parser: GpgPublicKeyParser,
) : GpgKeyserverClient {
    companion object {
        const val ROUTE_VKS_BY_FINGERPRINT = "gpg-keyserver-vks-by-fingerprint"
        const val ROUTE_VKS_BY_KEY_ID = "gpg-keyserver-vks-by-key-id"
        const val ROUTE_VKS_BY_EMAIL = "gpg-keyserver-vks-by-email"
        const val ROUTE_HKP_INDEX = "gpg-keyserver-hkp-index"
        const val ROUTE_HKP_GET = "gpg-keyserver-hkp-get"
        const val ROUTE_VKS_UPLOAD = "gpg-keyserver-vks-upload"
        const val ROUTE_HKP_ADD = "gpg-keyserver-hkp-add"

        private val emailRegex = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

        // keys.openpgp.org rate-limits its by-email VKS endpoint; keep at least
        // this interval between consecutive by-email lookups. Lives on the
        // (global-singleton) client so the throttle applies to every caller and
        // survives vault unlocks, not just a single verification session.
        private const val VKS_BY_EMAIL_THROTTLE_MILLIS = 65_000L
    }

    // Guards the by-email throttle state below. Accessed only under the mutex.
    private val vksByEmailMutex = Mutex()
    private var lastVksByEmailAtMillis: Long? = null

    constructor(
        directDI: DirectDI,
    ) : this(
        httpClient = directDI.instance(),
        parser = directDI.instance(),
    )

    override fun search(
        request: SearchGpgPublicKeyRequest,
        config: GpgKeyserverConfig,
    ): IO<List<DGpgKeyserverResult>> = ioEffect(Dispatchers.IO) {
        val query = request.query.trim()
        if (query.isEmpty()) {
            return@ioEffect emptyList()
        }

        val effectiveConfig = request.keyserverConfig
            ?.takeIf { it.url.trim().isNotEmpty() }
            ?.let { it.copy(url = it.url.trim()) }
            ?: request.keyserver
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { config.copy(url = it) }
            ?: config
        val mode = request.mode.resolve(query)
        when (effectiveConfig.protocol) {
            GpgKeyserverConfig.Protocol.VKS -> searchVks(
                query = query,
                mode = mode,
                config = effectiveConfig,
            )

            GpgKeyserverConfig.Protocol.HKP -> when (mode) {
                SearchGpgPublicKeyRequest.Mode.FINGERPRINT -> getHkpByFingerprint(
                    fingerprint = query,
                    config = effectiveConfig,
                )

                else -> searchHkp(
                    query = query,
                    config = effectiveConfig,
                )
            }
        }
            .distinctBy { it.fingerprint.normalizeGpgFingerprint() }
    }

    override fun getByFingerprint(
        fingerprint: String,
        config: GpgKeyserverConfig,
    ): IO<DGpgKeyserverResult?> = ioEffect(Dispatchers.IO) {
        val normalizedFingerprint = fingerprint.normalizeGpgFingerprint()
        if (normalizedFingerprint.isEmpty()) {
            throw IllegalArgumentException("The GPG key fingerprint is empty.")
        }

        when (config.protocol) {
            GpgKeyserverConfig.Protocol.VKS -> searchVks(
                query = normalizedFingerprint,
                mode = SearchGpgPublicKeyRequest.Mode.FINGERPRINT,
                config = config,
            )

            GpgKeyserverConfig.Protocol.HKP -> getHkpByFingerprint(
                fingerprint = normalizedFingerprint,
                config = config,
            )
        }
            .firstOrNull { it.fingerprint.normalizeGpgFingerprint() == normalizedFingerprint }
    }

    override fun canServeSearch(
        request: SearchGpgPublicKeyRequest,
        config: GpgKeyserverConfig,
    ): Boolean {
        val query = request.query.trim()
        if (query.isEmpty()) {
            // Nothing to serve; search() short-circuits to an empty result.
            return true
        }
        val effectiveConfig = request.keyserverConfig ?: config
        return when (effectiveConfig.protocol) {
            // HKP exposes a free-text index endpoint, so it can serve any mode.
            GpgKeyserverConfig.Protocol.HKP -> true
            // VKS only offers by-fingerprint / by-key-id / by-email lookups.
            GpgKeyserverConfig.Protocol.VKS ->
                request.mode.resolve(query) != SearchGpgPublicKeyRequest.Mode.TEXT
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
            GpgKeyserverConfig.Protocol.VKS -> throttleVksByEmail {
                searchVks(
                    query = query,
                    mode = SearchGpgPublicKeyRequest.Mode.EMAIL,
                    config = config,
                )
            }

            GpgKeyserverConfig.Protocol.HKP -> searchHkp(
                query = query,
                config = config,
            )
        }
            .distinctBy { it.fingerprint.normalizeGpgFingerprint() }
    }

    override fun upload(
        publicKeyArmored: String,
        config: GpgKeyserverConfig,
    ): IO<Unit> = ioEffect(Dispatchers.IO) {
        val keytext = publicKeyArmored.trim()
        if (keytext.isEmpty()) {
            throw IllegalArgumentException("The public GPG key is empty.")
        }

        when (config.protocol) {
            GpgKeyserverConfig.Protocol.VKS -> uploadVks(
                keytext = keytext,
                config = config,
            )

            GpgKeyserverConfig.Protocol.HKP -> uploadHkp(
                keytext = keytext,
                config = config,
            )
        }
    }

    /**
     * Serializes VKS by-email lookups and spaces them at least
     * [VKS_BY_EMAIL_THROTTLE_MILLIS] apart, waiting (never skipping or
     * erroring) when a call arrives too soon after the previous one.
     */
    private suspend fun <T> throttleVksByEmail(
        block: suspend () -> T,
    ): T = vksByEmailMutex.withLock {
        val last = lastVksByEmailAtMillis
        val now = Clock.System.now().toEpochMilliseconds()
        if (last != null) {
            val remaining = VKS_BY_EMAIL_THROTTLE_MILLIS - (now - last)
            if (remaining > 0L) {
                delay(remaining)
            }
        }

        try {
            block()
        } finally {
            lastVksByEmailAtMillis = Clock.System.now().toEpochMilliseconds()
        }
    }

    private suspend fun searchVks(
        query: String,
        mode: SearchGpgPublicKeyRequest.Mode,
        config: GpgKeyserverConfig,
    ): List<DGpgKeyserverResult> {
        val lookup = when (mode) {
            SearchGpgPublicKeyRequest.Mode.FINGERPRINT -> VksLookup(
                path = "by-fingerprint",
                value = query.normalizeGpgFingerprint(),
                route = ROUTE_VKS_BY_FINGERPRINT,
            )

            SearchGpgPublicKeyRequest.Mode.KEY_ID -> VksLookup(
                path = "by-keyid",
                value = query.normalizeGpgFingerprint(),
                route = ROUTE_VKS_BY_KEY_ID,
            )

            SearchGpgPublicKeyRequest.Mode.EMAIL -> VksLookup(
                path = "by-email",
                value = query,
                route = ROUTE_VKS_BY_EMAIL,
            )

            SearchGpgPublicKeyRequest.Mode.TEXT,
            SearchGpgPublicKeyRequest.Mode.AUTO,
                -> null
        }
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

    private suspend fun uploadVks(
        keytext: String,
        config: GpgKeyserverConfig,
    ) {
        val url = buildUrl(config.url) {
            appendPathSegments("vks", "v1", "upload")
        }
        val response = httpClient.post(url) {
            attributes.put(routeAttribute, ROUTE_VKS_UPLOAD)
            setBody(keytextForm(keytext))
        }
        response.requireSuccess()
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

    private suspend fun getHkpByFingerprint(
        fingerprint: String,
        config: GpgKeyserverConfig,
    ): List<DGpgKeyserverResult> {
        val normalizedFingerprint = fingerprint.normalizeGpgFingerprint()
        if (normalizedFingerprint.isEmpty()) {
            return emptyList()
        }

        val url = buildUrl(config.url) {
            appendPathSegments("pks", "lookup")
        }
        val response = httpClient.get(url) {
            attributes.put(routeAttribute, ROUTE_HKP_GET)
            parameter("op", "get")
            parameter("options", "mr")
            parameter("search", "0x$normalizedFingerprint")
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

    private suspend fun searchHkp(
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
                .map { key ->
                    key.toResult(sourceConfig)
                }

            is GpgPublicKeyParseResult.Error -> when (result.reason) {
                GpgPublicKeyParseError.Empty -> emptyList()
                GpgPublicKeyParseError.Malformed -> throw IllegalStateException(
                    "Keyserver returned malformed OpenPGP public key data.",
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
                        val keyId = parts.getOrNull(1)
                            ?.takeIf { it.isNotBlank() }
                            ?.normalizeGpgFingerprint()
                        current = HkpRecord(
                            keyId = keyId,
                            fingerprint = keyId?.takeIf { it.length > 16 },
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
                            .firstOrNull { it.isNotBlank() && it.normalizeGpgFingerprint().length >= 32 }
                            ?.normalizeGpgFingerprint()
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
                val fingerprint = record.fingerprint
                    ?: record.keyId
                    ?: return@mapNotNull null
                DGpgKeyserverResult(
                    fingerprint = fingerprint,
                    keyId = record.keyId?.takeLast(16),
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

    private fun SearchGpgPublicKeyRequest.Mode.resolve(
        query: String,
    ): SearchGpgPublicKeyRequest.Mode {
        if (this != SearchGpgPublicKeyRequest.Mode.AUTO) {
            return this
        }
        val normalizedHex = query.normalizeGpgFingerprint()
        return when {
            normalizedHex.length >= 32 && normalizedHex.all { it.isHexDigit() } ->
                SearchGpgPublicKeyRequest.Mode.FINGERPRINT

            normalizedHex.length in setOf(8, 16) && normalizedHex.all { it.isHexDigit() } ->
                SearchGpgPublicKeyRequest.Mode.KEY_ID

            emailRegex.matches(query) ->
                SearchGpgPublicKeyRequest.Mode.EMAIL

            else ->
                SearchGpgPublicKeyRequest.Mode.TEXT
        }
    }

    private fun buildUrl(
        baseUrl: String,
        block: URLBuilder.() -> Unit,
    ): String = URLBuilder(Url(baseUrl.ensureSuffix("/")))
        .apply(block)
        .buildString()

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

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
