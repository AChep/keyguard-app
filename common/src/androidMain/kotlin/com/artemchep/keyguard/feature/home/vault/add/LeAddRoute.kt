package com.artemchep.keyguard.feature.home.vault.add

import com.artemchep.keyguard.android.AutofillActivity
import com.artemchep.keyguard.android.AutofillSaveActivity
import com.artemchep.keyguard.android.autofill.AutofillStructure2
import com.artemchep.keyguard.common.model.AutofillHint
import com.artemchep.keyguard.common.util.Browsers

fun AddRoute.Args.Autofill.Companion.of(
    source: AutofillActivity.Args,
) = ofAutofillStructure(
    struct = source.autofillStructure2,
    applicationId = source.applicationId,
    webDomain = source.webDomain,
    webScheme = source.webScheme,
)

fun AddRoute.Args.Autofill.Companion.of(
    source: AutofillSaveActivity.Args,
) = ofAutofillStructure(
    struct = source.autofillStructure2,
    applicationId = source.applicationId,
    webDomain = source.webDomain,
    webScheme = source.webScheme,
)

private fun ofAutofillStructure(
    struct: AutofillStructure2?,
    applicationId: String? = null,
    webDomain: String? = null,
    webScheme: String? = null,
): AddRoute.Args.Autofill {
    val items = struct?.items.orEmpty()
    val email = items
        .firstOrNull { it.hint == AutofillHint.EMAIL_ADDRESS }
        ?.value
        ?.takeIf { it.isNotBlank() }
    val username = items
        .firstOrNull { it.hint == AutofillHint.NEW_USERNAME }
        ?.value
        ?.takeIf { it.isNotBlank() }
        ?: items
            .firstOrNull { it.hint == AutofillHint.USERNAME }
            ?.value
            ?.takeIf { it.isNotBlank() }
        ?: email
    val password = items
        .firstOrNull { it.hint == AutofillHint.NEW_PASSWORD }
        ?.value
        ?.takeIf { it.isNotBlank() }
        ?: items
            .firstOrNull { it.hint == AutofillHint.PASSWORD }
            ?.value
            ?.takeIf { it.isNotBlank() }
        ?: items
            .firstOrNull { it.hint == AutofillHint.WIFI_PASSWORD }
            ?.value
            ?.takeIf { it.isNotBlank() }
    val phone = items
        .firstOrNull { it.hint == AutofillHint.PHONE_NUMBER }
        ?.value
        ?.takeIf { it.isNotBlank() }
    val personName = items
        .firstOrNull { it.hint == AutofillHint.PERSON_NAME }
        ?.value
        ?.takeIf { it.isNotBlank() }
    return AddRoute.Args.Autofill(
        webDomain = webDomain,
        webScheme = webScheme,
        // we do not want to add browsers to
        // list of uris
        applicationId = applicationId
            ?.takeIf {
                it !in Browsers
            }
            ?.takeIf {
                webDomain == null ||
                        struct?.webView == true
            },
        // Login
        username = username,
        password = password,
        // Identity
        email = email,
        phone = phone,
        personName = personName,
    )
}
