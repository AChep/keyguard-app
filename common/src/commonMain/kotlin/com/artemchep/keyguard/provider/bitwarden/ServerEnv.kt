package com.artemchep.keyguard.provider.bitwarden

import arrow.optics.optics
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.StringResource
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

@optics
data class ServerEnv(
    val baseUrl: String = "",
    val webVaultUrl: String = "",
    val apiUrl: String = "",
    val identityUrl: String = "",
    val iconsUrl: String = "",
    val notificationsUrl: String = "",
    val region: Region = Region.default,
    val headers: List<ServerHeader> = emptyList(),
    val mutualTls: MutualTls? = null,
) {
    companion object;

    @Serializable
    sealed interface MutualTls {
        @Serializable
        @SerialName("imported_pkcs12")
        data class ImportedPkcs12(
            val pkcs12Base64: String,
        ) : MutualTls {
            /** Identifies the bundle; computed once and excluded from serialization. */
            val fingerprint: String by lazy {
                val bytes = Base64.Default.decode(pkcs12Base64)
                try {
                    Base64.Default.encode(NativeCryptoPrimitives.sha256(bytes))
                } finally {
                    bytes.fill(0)
                }
            }

            // The certificate bundle includes the private key.
            override fun toString(): String = "ImportedPkcs12(pkcs12Base64=<redacted>)"
        }
    }

    enum class Region(
        val title: StringResource,
        val text: String,
    ) {
        US(Res.string.addaccount_region_us_type, "bitwarden.com"),
        EU(Res.string.addaccount_region_eu_type, "bitwarden.eu"),
        ;

        companion object {
            val default get() = US

            // Must not collide with any of the
            // region names!
            val selfhosted get() = "selfhosted"
        }
    }
}

/** Matches the custom environment offered by account setup. */
fun ServerEnv.isCustom(): Boolean =
    listOf(baseUrl, webVaultUrl, apiUrl, identityUrl, iconsUrl, notificationsUrl)
        .any(String::isNotBlank) || headers.isNotEmpty() || mutualTls != null
