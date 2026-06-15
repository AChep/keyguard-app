package com.artemchep.keyguard.android.autofill

import com.artemchep.keyguard.android.autofill.v2.model.ParseResultV2
import com.artemchep.keyguard.android.autofill.v2.model.SemanticType
import com.artemchep.keyguard.common.model.AutofillHint

internal fun ParseResultV2.toAutofillStructure2(): AutofillStructure2 {
    val fieldValuesById = structure.fields.associate { it.id to it.value }
    val items = structure.fields
        .asSequence()
        .distinctBy { it.id }
        .mapNotNull { field ->
            fieldTypes[field.id]
                ?.toAutofillHint()
                ?.let { hint ->
                    AutofillStructure2.Item(
                        id = field.id,
                        hint = hint,
                        value = fieldValuesById[field.id],
                    )
                }
        }
        .toList()

    val isInSelfHostedServer = structure.webView &&
            (structure.webDomain == "127.0.0.1" || structure.webDomain == "localhost")

    return AutofillStructure2(
        applicationId = structure.applicationId,
        webDomain = structure.webDomain.takeUnless { isInSelfHostedServer },
        webScheme = structure.webScheme.takeUnless { isInSelfHostedServer },
        webView = structure.webView.takeUnless { isInSelfHostedServer },
        items = items,
    )
}

private fun SemanticType.toAutofillHint(): AutofillHint? =
    when (this) {
        SemanticType.USERNAME -> AutofillHint.USERNAME
        SemanticType.EMAIL_ADDRESS -> AutofillHint.EMAIL_ADDRESS
        SemanticType.PASSWORD -> AutofillHint.PASSWORD
        SemanticType.NEW_USERNAME -> AutofillHint.NEW_USERNAME
        SemanticType.NEW_PASSWORD -> AutofillHint.NEW_PASSWORD
        SemanticType.PHONE_NUMBER -> AutofillHint.PHONE_NUMBER
        SemanticType.OTP -> AutofillHint.APP_OTP
        SemanticType.PERSON_NAME -> AutofillHint.PERSON_NAME
        SemanticType.GIVEN_NAME -> AutofillHint.PERSON_NAME_GIVEN
        SemanticType.FAMILY_NAME -> AutofillHint.PERSON_NAME_FAMILY
        SemanticType.STREET_ADDRESS -> AutofillHint.POSTAL_ADDRESS_STREET_ADDRESS
        SemanticType.POSTAL_CODE -> AutofillHint.POSTAL_CODE
        SemanticType.COUNTRY -> AutofillHint.POSTAL_ADDRESS_COUNTRY
        SemanticType.REGION -> AutofillHint.POSTAL_ADDRESS_REGION
        SemanticType.LOCALITY -> AutofillHint.POSTAL_ADDRESS_LOCALITY
        SemanticType.CREDIT_CARD_NUMBER -> AutofillHint.CREDIT_CARD_NUMBER
        SemanticType.CREDIT_CARD_SECURITY_CODE -> AutofillHint.CREDIT_CARD_SECURITY_CODE
        SemanticType.CREDIT_CARD_EXPIRATION_DATE -> AutofillHint.CREDIT_CARD_EXPIRATION_DATE
        SemanticType.CREDIT_CARD_EXPIRATION_MONTH -> AutofillHint.CREDIT_CARD_EXPIRATION_MONTH
        SemanticType.CREDIT_CARD_EXPIRATION_YEAR -> AutofillHint.CREDIT_CARD_EXPIRATION_YEAR
        SemanticType.SEARCH,
        SemanticType.COMMENT,
        SemanticType.QUANTITY,
        SemanticType.CAPTCHA,
        SemanticType.CONSENT,
        SemanticType.UNKNOWN,
        -> null
    }
