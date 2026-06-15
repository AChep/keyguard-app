package com.artemchep.keyguard.android

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.provider.CallingAppInfo
import com.artemchep.keyguard.common.model.AddPrivilegedAppRequest
import com.artemchep.keyguard.common.model.DPrivilegedApp
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.gpmprivapps.PrivilegedAppsService
import com.artemchep.keyguard.common.service.passkey.entity.CreatePasskeyAttestation
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.service.webauthn.WebAuthnAuthenticatorDataFactory
import com.artemchep.keyguard.common.service.webauthn.WebAuthnRelatedOrigins
import com.artemchep.keyguard.common.service.webauthn.WebAuthnRpIdValidator
import com.artemchep.keyguard.common.service.webauthn.canonicalizeWebAuthnRpId
import com.artemchep.keyguard.common.service.webauthn.isValidCanonicalWebAuthnRpId
import com.artemchep.keyguard.common.service.webauthn.webAuthnUserVerifiedFlag
import io.ktor.client.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.lang.IllegalStateException

class PasskeyUtils(
    private val cryptoService: CryptoGenerator,
    privilegedAppsService: PrivilegedAppsService,
    tldService: TldService,
    httpClient: HttpClient,
) {
    companion object {
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

    private val callingAppOriginResolver = AndroidCallingAppOriginResolver(
        cryptoService = cryptoService,
        privilegedAppsService = privilegedAppsService,
    )
    private val webAuthnRpIdValidator = WebAuthnRpIdValidator(
        tldService = tldService,
        relatedOrigins = WebAuthnRelatedOrigins(
            tldService = tldService,
            httpClient = httpClient,
        ),
    )
    private val androidAssetLinksRpBinding = AndroidAssetLinksRpBinding(
        httpClient = httpClient,
    )
    private val authenticatorDataFactory = WebAuthnAuthenticatorDataFactory(
        cryptoService = cryptoService,
    )

    constructor(
        directDI: DirectDI,
    ) : this(
        cryptoService = directDI.instance(),
        privilegedAppsService = directDI.instance(),
        tldService = directDI.instance(),
        httpClient = directDI.instance(tag = "curl"),
    )

    @RequiresApi(Build.VERSION_CODES.P)
    fun callingAppPrivilegedRequest(
        appInfo: CallingAppInfo,
    ): AddPrivilegedAppRequest = callingAppOriginResolver.privilegedRequest(appInfo)

    @RequiresApi(Build.VERSION_CODES.P)
    suspend fun callingAppOrigin(
        appInfo: CallingAppInfo,
        privilegedApps: List<DPrivilegedApp>,
    ): String = callingAppOriginResolver.origin(
        appInfo = appInfo,
        privilegedApps = privilegedApps,
    )

    /**
     * Validates that the origin is known to the relaying party and
     * we can give a response to this request.
     */
    suspend fun requireRpMatchesOrigin(
        rpId: String,
        origin: String,
        packageName: String,
    ): Unit = runCatching {
        val normalizedRpId = webAuthnRpIdValidator.requireValidRpId(rpId)
        when {
            // WebAuthn L3 permits HTTP only for localhost web origins; the
            // web validator below enforces that host restriction.
            // See https://www.w3.org/TR/webauthn-3/#rp-id
            origin.startsWith("https:", ignoreCase = true) ||
                    origin.startsWith("http:", ignoreCase = true) ->
                webAuthnRpIdValidator.requireRpMatchesOrigin(
                    rpId = normalizedRpId,
                    origin = origin,
                )

            origin.startsWith("android:", ignoreCase = true) ->
                androidAssetLinksRpBinding.requireRpMatchesOrigin(
                    rpId = normalizedRpId,
                    origin = origin,
                    packageName = packageName,
                )

            else -> throw IllegalStateException("Request origin has an unknown scheme.")
        }
    }.getOrElse {
        val detailMessage = it.localizedMessage
            ?: it.message
        val fullMessage =
            "Failed to verify the relation with the `$rpId` relaying party. $detailMessage" +
                    "\n\n" + getGenericServiceFailureMessage(rpId)
        throw IllegalStateException(fullMessage)
    }

    /**
     * Resolves the ceremony RP ID and verifies that the caller is allowed to
     * use it before creating an attestation or assertion.
     *
     * WebAuthn binds every credential operation to an RP ID. For web origins,
     * an explicit RP ID must be equal to, or a registrable domain suffix of,
     * the caller origin's effective domain unless related-origin validation
     * succeeds. This helper performs the local RP ID defaulting step and then
     * delegates to [requireRpMatchesOrigin] for web-origin or Android-origin
     * binding checks.
     *
     * Spec:
     * - https://www.w3.org/TR/webauthn-3/#rp-id
     * - https://www.w3.org/TR/webauthn-3/#sctn-createCredential
     * - https://www.w3.org/TR/webauthn-3/#sctn-discover-from-external-source
     */
    suspend fun resolveAndValidateRpId(
        rpId: String?,
        origin: String,
        packageName: String,
    ): String {
        val resolvedRpId = resolveRpId(
            rpId = rpId,
            origin = origin,
        )
        requireRpMatchesOrigin(
            rpId = resolvedRpId,
            origin = origin,
            packageName = packageName,
        )
        return resolvedRpId
    }

    /**
     * Returns the canonical RP ID that should be used for a WebAuthn request.
     *
     * If the request supplies `rp.id` for create() or `rpId` for get(), the
     * caller-supplied value is canonicalized and returned for later validation.
     * If it is absent, WebAuthn defaults the RP ID to the caller origin's
     * effective domain; this client accepts HTTPS web origins and the
     * WebAuthn `http://localhost[:port]` development exception.
     */
    fun resolveRpId(
        rpId: String?,
        origin: String,
    ): String = webAuthnRpIdValidator.resolveRpId(
        rpId = rpId,
        origin = origin,
    )

    /**
     * Converts an RP ID candidate into the local canonical form used for
     * comparisons, credential lookup, and `rpIdHash` input.
     */
    fun canonicalizeRpId(
        value: String,
    ): String = canonicalizeWebAuthnRpId(value)

    /**
     * Checks whether [rpId] has the canonical RP ID syntax accepted by this
     * client before origin scoping is evaluated.
     */
    fun isValidCanonicalRpId(
        rpId: String,
    ): Boolean = isValidCanonicalWebAuthnRpId(rpId)

    fun generateCredentialId(): String = cryptoService.uuid()

    fun userVerification(
        mode: String?,
        userVerified: Boolean,
    ): Boolean = webAuthnUserVerifiedFlag(
        requirement = mode,
        userVerified = userVerified,
    )

    fun authData(
        rpId: String,
        counter: Int,
        credentialId: ByteArray,
        credentialPublicKey: ByteArray?,
        attestation: CreatePasskeyAttestation? = null,
        userVerification: Boolean,
        userPresence: Boolean,
    ): ByteArray = authenticatorDataFactory.encodeAuthenticatorData(
        rpId = rpId,
        signCount = counter,
        credentialId = credentialId,
        credentialPublicKey = credentialPublicKey,
        attestation = attestation,
        userVerified = userVerification,
        userPresent = userPresence,
    )

    private fun getGenericServiceFailureMessage(rpId: String): String =
        "This seems to be an issue with the service provider `$rpId`. Please reach out to their support team."
}
