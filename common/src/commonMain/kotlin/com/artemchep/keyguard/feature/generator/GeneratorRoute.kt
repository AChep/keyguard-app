package com.artemchep.keyguard.feature.generator

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route

data class GeneratorRoute(
    val args: Args = Args(),
) : Route {
    data class Args(
        val context: Context = Context(),
        val username: Boolean = false,
        val password: Boolean = false,
        val sshKey: Boolean = false,
        /**
         * If specified, adds a prefix to all the persisted fields
         * such as the email, configs etc.
         */
        val storageKey: String? = null,
    ) {
        data class Context(
            val uris: Array<String> = emptyArray(),
        ) {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (this::class != other?.let { it::class }) return false

                other as Context

                if (!uris.contentEquals(other.uris)) return false

                return true
            }

            override fun hashCode(): Int {
                return uris.contentHashCode()
            }
        }
    }

    @Composable
    override fun Content() {
        GeneratorScreen(
            args = args,
        )
    }
}
