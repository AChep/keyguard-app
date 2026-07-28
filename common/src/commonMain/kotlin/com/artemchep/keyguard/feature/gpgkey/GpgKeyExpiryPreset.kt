package com.artemchep.keyguard.feature.gpgkey

import com.artemchep.keyguard.common.model.GpgKeyExpiry
import com.artemchep.keyguard.common.service.crypto.GPG_KEY_EXPIRATION_MAX_DATE
import com.artemchep.keyguard.common.service.crypto.clampGpgKeyExpirationDate
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_key_expiry_never
import com.artemchep.keyguard.res.gpg_key_expiry_preset_custom
import com.artemchep.keyguard.res.gpg_key_expiry_preset_five_years
import com.artemchep.keyguard.res.gpg_key_expiry_preset_one_year
import com.artemchep.keyguard.res.gpg_key_expiry_preset_two_years
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.jetbrains.compose.resources.StringResource

/** Stable preset identities shared by GPG generation and expiration editing. */
enum class GpgKeyExpiryPreset(
    val key: String,
) {
    OneYear("one_year"),
    TwoYears("two_years"),
    FiveYears("five_years"),
    Never("never"),
    Custom("custom"),
    ;

    fun toPolicy(
        customDate: LocalDate? = null,
    ): GpgKeyExpiry? = when (this) {
        OneYear -> GpgKeyExpiry.AfterYears(1)
        TwoYears -> GpgKeyExpiry.AfterYears(2)
        FiveYears -> GpgKeyExpiry.AfterYears(5)
        Never -> GpgKeyExpiry.Never
        Custom -> customDate?.let { GpgKeyExpiry.OnDate(it) }
    }

    companion object {
        val default: GpgKeyExpiryPreset
            get() = FiveYears

        fun getOrDefault(
            key: String?,
            default: GpgKeyExpiryPreset = this.default,
        ): GpgKeyExpiryPreset = entries.firstOrNull { it.key == key } ?: default
    }
}

fun GpgKeyExpiryPreset.titleResource(
    never: StringResource = Res.string.gpg_key_expiry_never,
): StringResource = when (this) {
    GpgKeyExpiryPreset.OneYear -> Res.string.gpg_key_expiry_preset_one_year
    GpgKeyExpiryPreset.TwoYears -> Res.string.gpg_key_expiry_preset_two_years
    GpgKeyExpiryPreset.FiveYears -> Res.string.gpg_key_expiry_preset_five_years
    GpgKeyExpiryPreset.Never -> never
    GpgKeyExpiryPreset.Custom -> Res.string.gpg_key_expiry_preset_custom
}

fun defaultGpgKeyExpirationDate(
    currentDate: LocalDate,
): LocalDate = clampGpgKeyExpirationDate(
    currentDate.plus(GpgKeyExpiry.DEFAULT_YEARS, DateTimeUnit.YEAR),
)

/** Returns selectable future dates, or null when no safe future day remains. */
fun gpgKeyExpirationDateRange(
    currentDate: LocalDate,
    maximumDate: LocalDate = GPG_KEY_EXPIRATION_MAX_DATE,
): ClosedRange<LocalDate>? {
    val firstDate = currentDate.plus(1, DateTimeUnit.DAY)
    val lastDate = clampGpgKeyExpirationDate(maximumDate)
    return if (firstDate <= lastDate) firstDate..lastDate else null
}

fun normalizeGpgKeyCustomExpiryDate(
    rawDate: String,
    currentDate: LocalDate,
    maximumDate: LocalDate = currentDate.plus(100, DateTimeUnit.YEAR),
): LocalDate {
    val selectableDates = gpgKeyExpirationDateRange(
        currentDate = currentDate,
        maximumDate = maximumDate,
    )
    return runCatching { LocalDate.parse(rawDate) }
        .getOrNull()
        ?.takeIf { selectableDates?.contains(it) == true }
        ?: defaultGpgKeyExpirationDate(currentDate)
}
