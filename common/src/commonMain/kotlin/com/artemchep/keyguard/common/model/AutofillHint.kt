package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.feature.auth.common.util.REGEX_EMAIL
import com.artemchep.keyguard.feature.auth.common.util.REGEX_PHONE_NUMBER

enum class AutofillHint {
    EMAIL_ADDRESS,
    USERNAME,
    PASSWORD,
    WIFI_PASSWORD,
    POSTAL_ADDRESS,
    POSTAL_CODE,
    CREDIT_CARD_NUMBER,
    CREDIT_CARD_SECURITY_CODE,
    CREDIT_CARD_EXPIRATION_DATE,
    CREDIT_CARD_EXPIRATION_MONTH,
    CREDIT_CARD_EXPIRATION_YEAR,
    CREDIT_CARD_EXPIRATION_DAY,

    // extended
    POSTAL_ADDRESS_COUNTRY,
    POSTAL_ADDRESS_REGION,
    POSTAL_ADDRESS_LOCALITY,
    POSTAL_ADDRESS_STREET_ADDRESS,
    POSTAL_ADDRESS_EXTENDED_ADDRESS,
    POSTAL_ADDRESS_EXTENDED_POSTAL_CODE,
    POSTAL_ADDRESS_APT_NUMBER,
    POSTAL_ADDRESS_DEPENDENT_LOCALITY,
    PERSON_NAME,
    PERSON_NAME_GIVEN,
    PERSON_NAME_FAMILY,
    PERSON_NAME_MIDDLE,
    PERSON_NAME_MIDDLE_INITIAL,
    PERSON_NAME_PREFIX,
    PERSON_NAME_SUFFIX,
    PHONE_NUMBER,
    PHONE_NUMBER_DEVICE,
    PHONE_COUNTRY_CODE,
    PHONE_NATIONAL,
    NEW_USERNAME,
    NEW_PASSWORD,
    GENDER,
    BIRTH_DATE_FULL,
    BIRTH_DATE_DAY,
    BIRTH_DATE_MONTH,
    BIRTH_DATE_YEAR,
    SMS_OTP,
    EMAIL_OTP,
    APP_OTP,
    NOT_APPLICABLE,
    PROMO_CODE,
    UPI_VPA,

    // custom
    OFF,
}

data class AutofillMatcher(
    val hint: AutofillHint,
    val regex: Regex? = null,
)

val zzMap = mapOf<AutofillHint, List<AutofillMatcher>>(
    AutofillHint.USERNAME to listOf(
        // Username might be your email field.
        AutofillMatcher(
            hint = AutofillHint.EMAIL_ADDRESS,
            regex = REGEX_EMAIL,
        ),
    ),
    AutofillHint.PASSWORD to listOf(
        // Password might be your WiFi password.
        AutofillMatcher(
            hint = AutofillHint.WIFI_PASSWORD,
        ),
    ),
    AutofillHint.WIFI_PASSWORD to listOf(
        // WiFi password might be your regular password.
        AutofillMatcher(
            hint = AutofillHint.PASSWORD,
        ),
    ),
    AutofillHint.PHONE_NUMBER to listOf(
        AutofillMatcher(
            hint = AutofillHint.USERNAME,
            regex = REGEX_PHONE_NUMBER,
        ),
    ),
)
