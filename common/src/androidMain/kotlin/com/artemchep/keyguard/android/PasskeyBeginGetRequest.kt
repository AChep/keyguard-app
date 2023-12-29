package com.artemchep.keyguard.android

import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.CredentialEntry
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.artemchep.keyguard.android.downloader.journal.CipherHistoryOpenedRepository
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.PasskeyTarget
import com.artemchep.keyguard.common.usecase.PasskeyTargetCheck
import com.artemchep.keyguard.feature.auth.common.util.REGEX_IPV4
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

@SuppressLint("RestrictedApi")
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyBeginGetRequest(
    private val context: Context,
    private val json: Json,
    private val passkeyTargetCheck: PasskeyTargetCheck,
) {
    // https://www.w3.org/TR/webauthn-2/#dictionary-assertion-options
    @Serializable
    private data class PublicKeyCredentialRequestOptions(
        val allowCredentials: List<PublicKeyCredentialDescriptor> = emptyList(),
        @SerialName("challenge")
        val challengeBase64: String,
        val rpId: String,
        val userVerification: UserVerification? = UserVerification.PREFERRED,
        // https://www.w3.org/TR/webauthn-2/#enum-attestation-convey
        val attestation: String? = "none",
    ) {
        // https://www.w3.org/TR/webauthn-2/#enum-userVerificationRequirement
        enum class UserVerification {
            @SerialName("required")
            REQUIRED,

            @SerialName("preferred")
            PREFERRED,

            @SerialName("discouraged")
            DISCOURAGED,
        }

        // https://www.w3.org/TR/webauthn-2/#dictionary-credential-descriptor
        @Serializable
        data class PublicKeyCredentialDescriptor(
            val type: String,
            @SerialName("id")
            val idBase64: String,
            // https://www.w3.org/TR/webauthn-2/#enum-transport
            val transports: List<String> = emptyList(),
        )
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance<Application>(),
        json = directDI.instance(),
        passkeyTargetCheck = directDI.instance(),
    )

    suspend fun processGetCredentialsRequest(
        cipherHistoryOpenedRepository: CipherHistoryOpenedRepository,
        request: BeginGetCredentialRequest,
        ciphers: List<DSecret>,
        userVerified: Boolean = false,
    ): BeginGetCredentialResponse {
        val credentialEntries = request
            .beginGetCredentialOptions
            .flatMap { option ->
                when (option) {
                    is BeginGetPasswordOption -> populatePasswordData(
                        callingAppInfo = request.callingAppInfo,
                        option = option,
                        ciphers = ciphers,
                        userVerified = userVerified,
                    )

                    is BeginGetPublicKeyCredentialOption -> populatePasskeyData(
                        cipherHistoryOpenedRepository = cipherHistoryOpenedRepository,
                        callingAppInfo = request.callingAppInfo,
                        option = option,
                        ciphers = ciphers,
                        userVerified = userVerified,
                    )

                    else -> {
                        emptyList()
                    }
                }
            }
        return BeginGetCredentialResponse(credentialEntries)
    }

    private suspend fun populatePasswordData(
        callingAppInfo: CallingAppInfo?,
        option: BeginGetPasswordOption,
        ciphers: List<DSecret>,
        userVerified: Boolean,
    ): List<CredentialEntry> {
        return emptyList()
    }

    private suspend fun populatePasskeyData(
        cipherHistoryOpenedRepository: CipherHistoryOpenedRepository,
        callingAppInfo: CallingAppInfo?,
        option: BeginGetPublicKeyCredentialOption,
        ciphers: List<DSecret>,
        userVerified: Boolean,
    ): List<CredentialEntry> {
        val requestOptions: PublicKeyCredentialRequestOptions =
            json.decodeFromString(option.requestJson)
        // Ignore not allowed relying party IDs.
        if (!validateRpId(requestOptions.rpId)) {
            return emptyList()
        }
        val target = kotlin.run {
            val allowCredentials = requestOptions.allowCredentials
                .map { credentialDescriptor ->
                    val credentialId = kotlin.run {
                        val data = PasskeyBase64.decode(credentialDescriptor.idBase64)
                        PasskeyCredentialId.decode(data)
                    }
                    PasskeyTarget.AllowedCredential(
                        credentialId = credentialId,
                    )
                }
            PasskeyTarget(
                allowedCredentials = allowCredentials
                    .takeIf { it.isNotEmpty() },
                rpId = requestOptions.rpId,
            )
        }
        return ciphers
            .flatMap { cipher ->
                if (cipher.deleted) {
                    return@flatMap emptyList()
                }

                val credentials = cipher.login?.fido2Credentials.orEmpty()
                credentials
                    .mapNotNull { credential ->
                        val valid = passkeyTargetCheck(
                            credential,
                            target,
                        ).attempt().bind().isRight { it }
                        if (!valid) {
                            return@mapNotNull null
                        }
                        // At this moment we support a small set of credentials,
                        // for example we only support one algorithm + curve pair.
                        val supported = credential.keyAlgorithm == "ECDSA" &&
                                credential.keyCurve == "P-256" &&
                                credential.keyType == "public-key"
                        if (!supported) {
                            return@mapNotNull null
                        }

                        // Load last used time, this time might be used to sort the items
                        // on the system user interface.
                        val lastUsedTime = getCredentialLastUsedTimeOrNull(
                            cipherHistoryOpenedRepository = cipherHistoryOpenedRepository,
                            cipherId = cipher.id,
                            credentialId = credential.credentialId,
                        )
                        val requiresUserVerification = cipher.reprompt ||
                                requestOptions.userVerification == PublicKeyCredentialRequestOptions.UserVerification.REQUIRED

                        val username = credential.userDisplayName
                        // Normally the username should never be empty,
                        // be i've seen coinbase do that.
                            ?: "Unknown username"
                        PublicKeyCredentialEntry.Builder(
                            context = context,
                            username = username,
                            pendingIntent = createGetPasskeyPendingIntent(
                                accountId = cipher.accountId,
                                cipherId = cipher.id,
                                credId = credential.credentialId,
                                cipherName = cipher.name,
                                credRpId = credential.rpId,
                                credUserDisplayName = username,
                                requiresUserVerification = requiresUserVerification,
                                userVerified = userVerified,
                            ),
                            beginGetPublicKeyCredentialOption = option,
                        )
                            .setLastUsedTime(lastUsedTime?.toJavaInstant())
                            .setAutoSelectAllowed(lastUsedTime != null)
                            .setDisplayName(cipher.name)
                            .build()
                    }
            }
    }

    private suspend fun getCredentialLastUsedTimeOrNull(
        cipherHistoryOpenedRepository: CipherHistoryOpenedRepository,
        cipherId: String,
        credentialId: String,
    ): Instant? = cipherHistoryOpenedRepository
        .getCredentialLastUsed(
            cipherId = cipherId,
            credentialId = credentialId,
        )
        .toIO()
        .attempt()
        .bind()
        .getOrNull()

    // See:
    // https://webauthn-doc.spomky-labs.com/prerequisites/the-relying-party#relying-party-id
    private suspend fun validateRpId(
        rpId: String,
    ): Boolean = '/' !in rpId &&
            ':' !in rpId &&
            '@' !in rpId &&
            !REGEX_IPV4.matches(rpId)

    //
    // Pending intent
    //

    private fun createGetPasskeyPendingIntent(
        accountId: String,
        cipherId: String,
        credId: String,
        cipherName: String,
        credRpId: String,
        credUserDisplayName: String,
        requiresUserVerification: Boolean,
        userVerified: Boolean,
    ): PendingIntent {
        val intent = PasskeyGetActivity.getIntent(
            context = context,
            args = PasskeyGetActivity.Args(
                accountId = accountId,
                cipherId = cipherId,
                credId = credId,
                cipherName = cipherName,
                credRpId = credRpId,
                credUserDisplayName = credUserDisplayName,
                requiresUserVerification = requiresUserVerification,
                userVerified = userVerified,
            ),
        )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val rc = getNextPendingIntentRequestCode()
        return PendingIntent.getActivity(context, rc, intent, flags)
    }

    private fun getNextPendingIntentRequestCode() = PendingIntents.credential.obtainId()
}
