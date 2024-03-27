package com.artemchep.keyguard.core.store.bitwarden

import arrow.optics.optics
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
@optics
data class BitwardenService(
    val remote: Remote? = null,
    val error: Error? = null,
    val deleted: Boolean = false,
    val version: Int = 0,
) {
    companion object {
        const val VERSION = 13
    }

    @Serializable
    @optics
    data class Remote(
        val id: String,
        val revisionDate: Instant,
        val deletedDate: Instant?,
    ) {
        companion object
    }

    @Serializable
    @optics
    data class Error(
        val code: Int,
        val message: String? = null,
        val blob: String? = null,
        val revisionDate: Instant,
    ) {
        companion object {
            const val CODE_UNKNOWN = 0

            /**
             * Thrown to indicate that there
             * is an error in the underlying protocol,
             * such as a TCP error.
             */
            const val CODE_PROTOCOL_EXCEPTION = -3000
            const val CODE_UNKNOWN_HOST = -2000
            const val CODE_DECODING_FAILED = -1000
        }
    }

    interface Has<T> {
        val service: BitwardenService

        fun withService(
            service: BitwardenService,
        ): T
    }
}
