package com.artemchep.keyguard.feature.auth.login

import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.StringResource
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

sealed interface LoginRegion {
    companion object {
        val default get() = Predefined(ServerEnv.Region.default)

        val values: PersistentList<LoginRegion>
            get() = kotlin.run {
                val list = ServerEnv.Region.entries
                    .asSequence()
                    .map { region ->
                        val value: LoginRegion = Predefined(region)
                        value
                    }
                    .toPersistentList()
                list.add(Custom)
            }

        fun ofOrNull(key: String): LoginRegion? {
            val region = ServerEnv.Region.entries
                .firstOrNull { it.name == key }
            if (region != null) {
                return Predefined(region)
            }

            return when (key) {
                Custom.key -> Custom
                else -> null
            }
        }
    }

    val key: String
    val title: StringResource
    val text: String? get() = null

    @JvmInline
    value class Predefined(
        val region: ServerEnv.Region,
    ) : LoginRegion {
        override val key: String
            get() = region.name

        override val title: StringResource
            get() = region.title

        override val text: String
            get() = region.text
    }

    data object Custom : LoginRegion {
        override val key: String
            get() = ServerEnv.Region.selfhosted

        override val title: StringResource
            get() = Res.strings.addaccount_region_custom_type
    }
}
