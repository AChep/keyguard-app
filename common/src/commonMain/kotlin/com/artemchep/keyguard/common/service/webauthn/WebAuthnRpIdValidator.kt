package com.artemchep.keyguard.common.service.webauthn

import com.artemchep.keyguard.common.service.tld.TldService
import io.ktor.http.Url

internal class WebAuthnRpIdValidator(
    private val tldService: TldService,
    private val relatedOrigins: WebAuthnRelatedOrigins,
) {
    fun resolveRpId(
        rpId: String?,
        origin: String,
    ): String = rpId
        ?.let(::canonicalizeWebAuthnRpId)
        ?: defaultRpId(origin)

    suspend fun requireRpMatchesOrigin(
        rpId: String,
        origin: String,
    ) {
        val originUrl = Url(origin)
        val webOrigin = requireWebAuthnOrigin(originUrl)
        val normalizedRpId = requireValidRpId(rpId)

        // WebAuthn L3 first tries the direct RP ID rule: the requested RP ID
        // must equal, or be a registrable domain suffix of, the caller origin's
        // effective domain. If that fails, related-origin validation below can
        // still allow a non-suffix RP ID through the well-known document.
        // See:
        // https://www.w3.org/TR/webauthn-3/#rp-id
        // https://www.w3.org/TR/webauthn-3/#sctn-related-origins
        if (isWebAuthnDomainSuffix(
                tldService = tldService,
                domain = normalizedRpId,
                request = webOrigin.host,
            )
        ) {
            return
        }

        relatedOrigins.requireRpMatchesRelatedOrigin(
            rpId = normalizedRpId,
            origin = webOrigin,
        )
    }

    suspend fun requireValidRpId(
        rpId: String,
    ): String {
        // Validate RP IDs after URL domain-to-ASCII conversion, matching the
        // WebAuthn definition of RP ID as a URL valid domain string.
        // See https://www.w3.org/TR/webauthn-3/#rp-id
        // See https://url.spec.whatwg.org/#valid-domain-string
        val normalizedRpId = canonicalizeWebAuthnRpIdOrNull(rpId)
            ?: throw IllegalArgumentException("Relying party ID '$rpId' is not a valid domain.")
        require(isValidCanonicalWebAuthnRpId(normalizedRpId)) {
            "Relying party ID '$rpId' is not a valid domain."
        }
        require(isRpIdNotPublicSuffix(normalizedRpId)) {
            "Relying party ID '$rpId' must not be a public suffix."
        }
        return normalizedRpId
    }

    private fun defaultRpId(
        origin: String,
    ): String {
        val originUrl = runCatching {
            Url(origin)
        }.getOrElse {
            throw IllegalStateException("Request origin is not a valid URL.")
        }
        val webOrigin = requireWebAuthnOrigin(originUrl)

        // WebAuthn L3 defaults missing create() `pkOptions.rp.id` and get()
        // `pkOptions.rpId` values to the caller origin's effective domain.
        // See:
        // https://www.w3.org/TR/webauthn-3/#sctn-createCredential
        // https://www.w3.org/TR/webauthn-3/#sctn-discover-from-external-source
        return webOrigin.host
    }

    private suspend fun isRpIdNotPublicSuffix(
        rpId: String,
    ): Boolean {
        if (isWebAuthnLocalhost(rpId)) {
            return true
        }

        // This is the syntax/public-suffix gate for RP IDs. The origin binding
        // decision happens separately in requireRpMatchesOrigin(): first via the
        // direct equal-or-registrable-suffix rule, then via related-origin
        // validation when the direct rule fails. Removing this check would allow
        // public suffix RP IDs, widening credential scope across unrelated sites.
        // See:
        // https://www.w3.org/TR/webauthn-3/#rp-id
        // https://www.w3.org/TR/webauthn-3/#sctn-createCredential
        // https://www.w3.org/TR/webauthn-3/#sctn-discover-from-external-source
        return getWebAuthnRegistrableDomain(
            tldService = tldService,
            host = rpId,
        ) != null
    }
}
