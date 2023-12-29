package com.artemchep.keyguard.common.model

import arrow.optics.optics
import kotlinx.datetime.Instant
import org.kodein.di.DI

@optics
sealed interface MasterSession {
    companion object;

    @optics
    data class Key(
        val masterKey: MasterKey,
        val di: DI,
        val origin: Origin,
        val createdAt: Instant,
    ) : MasterSession {
        companion object;

        sealed interface Origin

        /**
         * The key was loaded from the file system. You can not
         * assume that the user is authenticated.
         */
        data object Persisted : Origin

        /**
         * The key was typed by a user. You can assume that the
         * user is authenticated.
         */
        data object Authenticated : Origin
    }

    @optics
    data class Empty(
        val reason: String? = null,
    ) : MasterSession {
        companion object
    }
}
