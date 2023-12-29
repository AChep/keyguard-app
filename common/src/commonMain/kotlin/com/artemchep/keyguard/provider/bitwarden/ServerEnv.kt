package com.artemchep.keyguard.provider.bitwarden

import arrow.optics.optics
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.StringResource

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
) {
    companion object;

    enum class Region(
        val title: StringResource,
        val text: String,
    ) {
        US(Res.strings.addaccount_region_us_type, "bitwarden.com"),
        EU(Res.strings.addaccount_region_eu_type, "bitwarden.eu"),
        ;

        companion object {
            val default get() = US

            // Must not collide with any of the
            // region names!
            val selfhosted get() = "selfhosted"
        }
    }
}

data class ServerHeader(
    val key: String,
    val value: String,
) {
    companion object
}
