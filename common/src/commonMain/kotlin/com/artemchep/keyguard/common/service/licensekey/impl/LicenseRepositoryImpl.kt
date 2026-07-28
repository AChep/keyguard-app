package com.artemchep.keyguard.common.service.licensekey.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.licensekey.LicenseEntitlementProofExpectation
import com.artemchep.keyguard.common.service.licensekey.LicenseEntitlementProofVerifier
import com.artemchep.keyguard.common.service.licensekey.LicenseEntitlementRequestKind
import com.artemchep.keyguard.common.service.licensekey.LicenseRepository
import com.artemchep.keyguard.common.service.licensekey.LicenseServerConfig
import com.artemchep.keyguard.common.service.licensekey.entity.ClaimAppleRequestEntity
import com.artemchep.keyguard.common.service.licensekey.entity.ClaimGoogleRequestEntity
import com.artemchep.keyguard.common.service.licensekey.entity.LicenseEntitlementEntity
import com.artemchep.keyguard.common.service.licensekey.entity.LicenseStatusRequestEntity
import com.artemchep.keyguard.common.service.licensekey.entity.SignedEntitlementEntity
import com.artemchep.keyguard.common.service.licensekey.licenseEntitlementRequestHash
import com.artemchep.keyguard.common.service.licensekey.model.LicenseClaim
import com.artemchep.keyguard.provider.bitwarden.api.builder.bodyOrApiException
import com.artemchep.keyguard.provider.bitwarden.api.builder.routeAttribute
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlin.io.encoding.Base64
import org.kodein.di.DirectDI
import org.kodein.di.instance

class LicenseRepositoryImpl(
    private val httpClient: HttpClient,
    private val config: LicenseServerConfig,
    private val cryptoGenerator: CryptoGenerator,
    private val proofVerifier: LicenseEntitlementProofVerifier,
) : LicenseRepository {
    companion object {
        private const val ROUTE_CLAIM_GOOGLE = "license-claim-google"
        private const val ROUTE_CLAIM_APPLE = "license-claim-apple"
        private const val ROUTE_STATUS = "license-status"

        private const val PATH_CLAIM_GOOGLE = "v1/license/claim/google"
        private const val PATH_CLAIM_APPLE = "v1/license/claim/apple"
        private const val PATH_STATUS = "v1/license/status"
    }

    constructor(directDI: DirectDI) : this(
        httpClient = directDI.instance(tag = "curl"),
        config = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        proofVerifier = directDI.instance(),
    )

    override fun claim(claim: LicenseClaim): IO<LicenseEntitlementEntity> = when (claim) {
        is LicenseClaim.Google -> postSigned(
            path = PATH_CLAIM_GOOGLE,
            route = ROUTE_CLAIM_GOOGLE,
            kind = LicenseEntitlementRequestKind.CLAIM_GOOGLE,
            values = listOf(
                claim.purchaseToken.trim(),
                claim.productId.trim(),
                claim.productType.normalizedGoogleProductType(),
            ),
        ) { challenge ->
            ClaimGoogleRequestEntity(
                challenge = challenge,
                purchaseToken = claim.purchaseToken,
                productId = claim.productId,
                productType = claim.productType.normalizedGoogleProductType(),
            )
        }

        is LicenseClaim.Apple -> postSigned(
            path = PATH_CLAIM_APPLE,
            route = ROUTE_CLAIM_APPLE,
            kind = LicenseEntitlementRequestKind.CLAIM_APPLE,
            values = listOf(claim.signedTransactionInfo.trim()),
        ) { challenge ->
            ClaimAppleRequestEntity(
                challenge = challenge,
                signedTransactionInfo = claim.signedTransactionInfo,
            )
        }
    }

    override fun status(
        licenseKey: String,
    ): IO<LicenseEntitlementEntity> = ioEffect(Dispatchers.IO) {
        val challenge = newChallenge()
        val url = buildUrl(PATH_STATUS)
        val body = LicenseStatusRequestEntity(
            challenge = challenge,
            licenseKey = licenseKey,
        )
        val signed = httpClient
            .post(url) {
                contentType(ContentType.Application.Json)
                setBody(body)
                attributes.put(routeAttribute, ROUTE_STATUS)
            }
            .bodyOrApiException<SignedEntitlementEntity>()
        verifySigned(
            signed = signed,
            challenge = challenge,
            kind = LicenseEntitlementRequestKind.LICENSE_STATUS,
            values = listOf(licenseKey.trim()),
        )
    }

    private inline fun <reified T : Any> postSigned(
        path: String,
        route: String,
        kind: String,
        values: List<String>,
        crossinline provideBody: (challenge: String) -> T,
    ): IO<LicenseEntitlementEntity> = ioEffect(Dispatchers.IO) {
        val challenge = newChallenge()
        val url = buildUrl(path)
        val signed = httpClient
            .post(url) {
                contentType(ContentType.Application.Json)
                setBody(provideBody(challenge))
                attributes.put(routeAttribute, route)
            }
            .bodyOrApiException<SignedEntitlementEntity>()
        verifySigned(
            signed = signed,
            challenge = challenge,
            kind = kind,
            values = values,
        )
    }

    private fun verifySigned(
        signed: SignedEntitlementEntity,
        challenge: String,
        kind: String,
        values: List<String>,
    ): LicenseEntitlementEntity = requireNotNull(
        proofVerifier.verify(
            signed = signed,
            expectation = LicenseEntitlementProofExpectation(
                challenge = challenge,
                requestKind = kind,
                requestHash = licenseEntitlementRequestHash(
                    kind = kind,
                    values = values,
                    hashSha256 = cryptoGenerator::hashSha256,
                ),
            ),
        ),
    ) {
        "License server response is invalid."
    }

    private fun newChallenge(): String = base64Url.encode(cryptoGenerator.seed(16))

    private fun buildUrl(path: String): String =
        "${config.baseUrl.trimEnd('/')}/$path"
}

private fun String.normalizedGoogleProductType(): String =
    if (this == "inapp" || this == "INAPP") {
        "inapp"
    } else {
        "subscription"
    }

private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
