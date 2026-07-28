package com.artemchep.keyguard.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NavItemsConfig(
    val version: Int = NavItemsConfigDefaults.VERSION,
    val items: List<NavItemSpec> = NavItemsConfigDefaults.defaultItems(),
)

@Serializable
data class NavItemSpec(
    val ref: NavItemRef,
    val visible: Boolean = true,
)

@Serializable
sealed interface NavItemRef {
    @Serializable
    @SerialName("built_in")
    data class BuiltIn(
        val key: String,
    ) : NavItemRef

    @Serializable
    @SerialName("cipher_filter")
    data class CipherFilter(
        val id: String,
    ) : NavItemRef

    @Serializable
    @SerialName("predefined_route")
    data class PredefinedRoute(
        val key: String,
    ) : NavItemRef
}

object NavItemsConfigDefaults {
    const val VERSION = 1

    const val BUILT_IN_VAULT = "vault"
    const val BUILT_IN_SENDS = "sends"
    const val BUILT_IN_GENERATOR = "generator"
    const val BUILT_IN_GPG_TOOLS = "gpg_tools"
    const val BUILT_IN_WATCHTOWER = "watchtower"
    const val BUILT_IN_SETTINGS = "settings"

    val builtInKeys = listOf(
        BUILT_IN_VAULT,
        BUILT_IN_SENDS,
        BUILT_IN_GENERATOR,
        BUILT_IN_GPG_TOOLS,
        BUILT_IN_WATCHTOWER,
        BUILT_IN_SETTINGS,
    )

    fun defaultItems(): List<NavItemSpec> = builtInKeys
        .map { key ->
            NavItemSpec(
                ref = NavItemRef.BuiltIn(key),
            )
        }

    fun defaultConfig() = NavItemsConfig(
        version = VERSION,
        items = defaultItems(),
    )
}
