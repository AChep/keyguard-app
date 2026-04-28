package com.artemchep.keyguard.android

import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.provider.BeginGetCredentialOption
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.CredentialEntry
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.PublicKeyCredentialEntry
import arrow.optics.Getter
import com.artemchep.keyguard.android.downloader.journal.CipherHistoryOpenedRepository
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.AutofillHint
import com.artemchep.keyguard.common.model.AutofillTarget
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.LinkInfoPlatform
import com.artemchep.keyguard.common.service.gpmprivapps.PrivilegedAppsService
import com.artemchep.keyguard.common.usecase.GetAutofillPasskeysEnabled
import com.artemchep.keyguard.common.usecase.GetAutofillPasswordsEnabled
import com.artemchep.keyguard.common.usecase.GetSuggestions
import com.artemchep.keyguard.common.usecase.PasskeyTarget
import com.artemchep.keyguard.common.usecase.PasskeyTargetCheck
import com.artemchep.keyguard.feature.auth.common.util.REGEX_IPV4
import io.ktor.http.Url
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Instant
import kotlin.time.toJavaInstant

@SuppressLint("RestrictedApi")
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyBeginGetRequest(
    private val context: Context,
    private val json: Json,
    private val getAutofillPasskeysEnabled: GetAutofillPasskeysEnabled,
    private val getAutofillPasswordsEnabled: GetAutofillPasswordsEnabled,
    private val passkeyTargetCheck: PasskeyTargetCheck,
    private val privilegedAppsService: PrivilegedAppsService,
    private val credentialProviderPlatformConfig: CredentialProviderPlatformConfig,
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
        getAutofillPasskeysEnabled = directDI.instance(),
        getAutofillPasswordsEnabled = directDI.instance(),
        passkeyTargetCheck = directDI.instance(),
        privilegedAppsService = directDI.instance(),
        credentialProviderPlatformConfig = directDI.instance(),
    )

    suspend fun processGetCredentialsRequest(
        cipherHistoryOpenedRepository: CipherHistoryOpenedRepository,
        getSuggestions: GetSuggestions<Any?>,
        equivalentDomainsBuilderFactory: EquivalentDomainsBuilderFactory,
        request: BeginGetCredentialRequest,
        ciphers: List<DSecret>,
        userVerified: Boolean = false,
    ): BeginGetCredentialResponse {
        val passkeysEnabled = getAutofillPasskeysEnabled().toIO().bind()
        val passwordsEnabled = getAutofillPasswordsEnabled().toIO().bind()
        val credentialEntries = filterCredentialProviderBeginGetOptions(
            options = request.beginGetCredentialOptions,
            passkeysEnabled = passkeysEnabled,
            passwordsEnabled = passwordsEnabled,
        )
            .flatMap { option ->
                when (option) {
                    is BeginGetPasswordOption -> populatePasswordData(
                        callingAppInfo = request.callingAppInfo,
                        option = option,
                        ciphers = ciphers,
                        getSuggestions = getSuggestions,
                        equivalentDomainsBuilderFactory = equivalentDomainsBuilderFactory,
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
        getSuggestions: GetSuggestions<Any?>,
        equivalentDomainsBuilderFactory: EquivalentDomainsBuilderFactory,
        userVerified: Boolean,
    ): List<CredentialEntry> {
        return findCredentialProviderPasswordCiphers(
            callingAppInfo = callingAppInfo,
            option = option,
            ciphers = ciphers,
            provideTrustedOrigin = { appInfo ->
                getCredentialProviderTrustedOriginOrNull(
                    appInfo = appInfo,
                    privilegedAppsService = privilegedAppsService,
                )
            },
            getSuggestions = { target, passwordCiphers ->
                getSuggestions(
                    passwordCiphers,
                    Getter { it as DSecret },
                    target,
                    equivalentDomainsBuilderFactory,
                )
                    .bind()
                    .mapNotNull { it as? DSecret }
            },
        )
            .map { cipher ->
                val login = requireNotNull(cipher.login)
                val username = requireNotNull(login.username)
                val requiresUserVerification = cipher.reprompt
                PasswordCredentialEntry.Builder(
                    context = context,
                    username = username,
                    pendingIntent = createGetPasswordPendingIntent(
                        args = PasswordProviderGetActivityArgs(
                            accountId = cipher.accountId,
                            cipherId = cipher.id,
                            id = username,
                            requiresUserVerification = requiresUserVerification,
                            userVerified = userVerified,
                        ),
                    ),
                    beginGetPasswordOption = option,
                )
                    .setDisplayName(cipher.name)
                    .build()
            }
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
                if (
                    cipher.archived ||
                    cipher.deleted
                ) {
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
                                requestOptions.userVerification == PublicKeyCredentialRequestOptions.UserVerification.PREFERRED ||
                                requestOptions.userVerification == PublicKeyCredentialRequestOptions.UserVerification.REQUIRED

                        val username = credential.userDisplayName
                        // Normally the username should never be empty,
                        // be i've seen coinbase do that.
                            ?: "Unknown username"
                        PublicKeyCredentialEntry.Builder(
                            context = context,
                            username = username,
                            pendingIntent = createGetPasskeyPendingIntent(
                                args = PasskeyProviderGetActivityArgs(
                                    accountId = cipher.accountId,
                                    cipherId = cipher.id,
                                    credId = credential.credentialId,
                                    cipherName = cipher.name,
                                    credRpId = credential.rpId,
                                    credUserDisplayName = username,
                                    requiresUserVerification = requiresUserVerification,
                                    userVerified = userVerified,
                                ),
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
        args: PasskeyProviderGetActivityArgs,
    ): PendingIntent {
        val intent = Intent(
            context,
            credentialProviderPlatformConfig.getPasskeyActivityClass,
        ).apply {
            putExtra(
                PasskeyGetActivity.KEY_ARGUMENTS,
                args,
            )
        }
        return createCredentialProviderPendingIntent(
            context = context,
            intent = intent,
        )
    }

    private fun createGetPasswordPendingIntent(
        args: PasswordProviderGetActivityArgs,
    ): PendingIntent {
        val intent = Intent(
            context,
            credentialProviderPlatformConfig.getPasswordActivityClass,
        ).apply {
            putExtra(
                PasswordGetActivity.KEY_ARGUMENTS,
                args,
            )
        }
        return createCredentialProviderPendingIntent(
            context = context,
            intent = intent,
        )
    }
}

internal fun filterCredentialProviderBeginGetOptions(
    options: List<BeginGetCredentialOption>,
    passkeysEnabled: Boolean,
    passwordsEnabled: Boolean,
): List<BeginGetCredentialOption> = options.filter { option ->
    when (option) {
        is BeginGetPublicKeyCredentialOption -> passkeysEnabled
        is BeginGetPasswordOption -> passwordsEnabled
        else -> false
    }
}

internal suspend fun findCredentialProviderPasswordCiphers(
    callingAppInfo: CallingAppInfo?,
    option: BeginGetPasswordOption,
    ciphers: List<DSecret>,
    provideTrustedOrigin: suspend (CallingAppInfo) -> String?,
    getSuggestions: suspend (AutofillTarget, List<DSecret>) -> List<DSecret>,
): List<DSecret> {
    val passwordCiphers = ciphers.filter { cipher ->
        if (
            cipher.archived ||
            cipher.deleted ||
            cipher.type != DSecret.Type.Login
        ) {
            return@filter false
        }

        val login = cipher.login ?: return@filter false
        if (
            login.username == null ||
            login.password == null
        ) {
            return@filter false
        }

        option.allowedUserIds.isEmpty() || login.username in option.allowedUserIds
    }
    if (passwordCiphers.isEmpty()) {
        return emptyList()
    }

    val target = createCredentialProviderAutofillTarget(
        callingAppInfo = callingAppInfo,
        trustedOrigin = if (callingAppInfo != null) {
            provideTrustedOrigin(callingAppInfo)
        } else {
            null
        },
        option = option,
    ) ?: return passwordCiphers

    return getSuggestions(target, passwordCiphers)
}

internal fun createCredentialProviderAutofillTarget(
    callingAppInfo: CallingAppInfo?,
    trustedOrigin: String?,
    option: BeginGetPasswordOption,
): AutofillTarget? {
    val links = buildList {
        callingAppInfo?.packageName?.let { packageName ->
            add(
                LinkInfoPlatform.Android(
                    packageName = packageName,
                ),
            )
        }
        trustedOrigin
            ?.takeIf { origin ->
                origin.startsWith("https://", ignoreCase = true) ||
                        origin.startsWith("http://", ignoreCase = true)
            }
            ?.let { origin ->
                runCatching {
                    Url(origin)
                }.getOrNull()
            }
            ?.let { url ->
                add(
                    LinkInfoPlatform.Web(
                        url = url,
                        frontPageUrl = url,
                    ),
                )
            }
    }
    if (links.isEmpty() && option.allowedUserIds.isEmpty()) {
        return null
    }

    return AutofillTarget(
        username = option.allowedUserIds.singleOrNull(),
        links = links,
        hints = listOf(
            AutofillHint.EMAIL_ADDRESS,
            AutofillHint.USERNAME,
            AutofillHint.PASSWORD,
        ),
    )
}

internal suspend fun getCredentialProviderTrustedOriginOrNull(
    appInfo: CallingAppInfo,
    privilegedAppsService: PrivilegedAppsService,
): String? {
    if (!appInfo.isOriginPopulated()) {
        return null
    }

    return runCatching {
        val privilegedAllowlist = privilegedAppsService.get().bind()
        appInfo.getOrigin(privilegedAllowlist)
    }.getOrNull()
}
