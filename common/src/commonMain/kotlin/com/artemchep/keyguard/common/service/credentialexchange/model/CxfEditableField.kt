package com.artemchep.keyguard.common.service.credentialexchange.model

import kotlinx.serialization.Serializable

/**
 * A user-editable value carried by a credential (e.g. a basic-auth username or
 * password).
 */
@Serializable
data class CxfEditableField(
    /**
     * The kind of the field, e.g. [FIELD_TYPE_STRING] or
     * [FIELD_TYPE_CONCEALED_STRING].
     */
    val fieldType: String,
    val value: String,
    val id: String? = null,
    val label: String? = null,
) {
    companion object {
        const val FIELD_TYPE_STRING = "string"
        const val FIELD_TYPE_CONCEALED_STRING = "concealed-string"
        const val FIELD_TYPE_EMAIL = "email"
        const val FIELD_TYPE_NUMBER = "number"
        const val FIELD_TYPE_BOOLEAN = "boolean"

        /**
         * A calendar date in the RFC 3339 full-date format,
         * `YYYY-MM-DD`.
         */
        const val FIELD_TYPE_DATE = "date"
        const val FIELD_TYPE_YEAR_MONTH = "year-month"
        const val FIELD_TYPE_WIFI_NETWORK_SECURITY_TYPE = "wifi-network-security-type"
        const val FIELD_TYPE_COUNTRY_CODE = "country-code"
        const val FIELD_TYPE_SUBDIVISION_CODE = "subdivision-code"
    }
}
