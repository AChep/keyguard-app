package com.artemchep.keyguard.feature.auth.bitwarden

import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.addaccount_region_custom_type
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import org.jetbrains.compose.resources.StringResource

sealed interface BitwardenLoginRegion {
    companion object {
        val default get() = Predefined(ServerEnv.Region.default)

        val values: PersistentList<BitwardenLoginRegion>
            get() = run {
                val list = ServerEnv.Region.entries
                    .asSequence()
                    .map { region ->
                        val value: BitwardenLoginRegion = Predefined(region)
                        value
                    }
                    .toPersistentList()
                list.add(Custom)
            }

        fun ofOrNull(key: String): BitwardenLoginRegion? {
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
    ) : BitwardenLoginRegion {
        override val key: String
            get() = region.name

        override val title: StringResource
            get() = region.title

        override val text: String
            get() = region.text
    }

    data object Custom : BitwardenLoginRegion {
        override val key: String
            get() = ServerEnv.Region.selfhosted

        override val title: StringResource
            get() = Res.string.addaccount_region_custom_type
    }
}