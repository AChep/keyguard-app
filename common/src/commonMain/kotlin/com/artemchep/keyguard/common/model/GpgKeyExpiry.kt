package com.artemchep.keyguard.common.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Expiration policy resolved against the actual key-creation context. */
@Serializable
sealed interface GpgKeyExpiry {
    @Serializable
    @SerialName("after_years")
    data class AfterYears(
        @SerialName("years")
        val years: Int,
    ) : GpgKeyExpiry {
        init {
            require(years > 0) { "GPG key expiry must be at least one year." }
        }
    }

    @Serializable
    @SerialName("on_date")
    data class OnDate(
        @SerialName("date")
        val date: LocalDate,
    ) : GpgKeyExpiry

    @Serializable
    @SerialName("never")
    data object Never : GpgKeyExpiry

    @Serializable
    @SerialName("at")
    data class At(
        @SerialName("epochSeconds")
        val epochSeconds: Long,
    ) : GpgKeyExpiry {
        constructor(instant: Instant) : this(instant.epochSeconds)

        val instant: Instant
            get() = Instant.fromEpochSeconds(epochSeconds)
    }

    companion object {
        const val DEFAULT_YEARS = 5

        val default: GpgKeyExpiry
            get() = AfterYears(DEFAULT_YEARS)
    }
}
