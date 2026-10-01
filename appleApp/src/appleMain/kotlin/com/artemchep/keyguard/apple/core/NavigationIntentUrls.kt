package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.URL_APPLE_MAPS
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import io.ktor.http.encodeURLParameter

// URLs the host's open-url handler opens for the platform navigation intents
// (UIApplication / NSWorkspace pick the app: Mail, Phone / FaceTime, Messages, Maps).

/**
 * Builds a `mailto:` URL (address / subject / body percent-encoded) from a
 * feedback / contact-us send intent. Shared by the navigation-stack interceptor
 * and the standalone "Contact us" sheet so both open mail the same way via the
 * host's open-url handler.
 */
internal fun NavigationIntent.NavigateToEmail.toMailtoUrl(): String {
    // The address comes from vault items too; encode it so a '?' / '&' / '#'
    // can't inject headers. RFC 6068 allows a literal '@'.
    val address = email.encodeURLParameter().replace("%40", "@")
    val params = buildList {
        subject?.let { add("subject=" + it.encodeURLParameter()) }
        body?.let { add("body=" + it.encodeURLParameter()) }
    }
    return "mailto:" + address + if (params.isNotEmpty()) "?" + params.joinToString("&") else ""
}

/** Builds a `tel:` URL, keeping only the characters a dialer accepts. */
internal fun NavigationIntent.NavigateToPhone.toTelUrl(): String =
    "tel:" + phoneNumber.toDialString()

/** Builds an `sms:` URL, keeping only the characters a dialer accepts. */
internal fun NavigationIntent.NavigateToSms.toSmsUrl(): String =
    "sms:" + phoneNumber.toDialString()

/** Builds an Apple Maps search URL for the intent's address. */
internal fun NavigationIntent.NavigateToMaps.toMapsUrl(): String {
    val address = listOfNotNull(address1, address2, address3, city, state, postalCode, country)
        .filter { it.isNotBlank() }
        .joinToString(separator = ", ")
    return "$URL_APPLE_MAPS?q=" + address.encodeURLParameter()
}

// Spaces and brackets make the URL invalid, so they have to go.
private fun String.toDialString(): String = filter { it.isDigit() || it in "+*#,;" }
