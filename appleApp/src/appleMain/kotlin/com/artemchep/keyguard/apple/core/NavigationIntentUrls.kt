package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.feature.navigation.NavigationIntent

// URLs the host's open-url handler opens for the platform navigation intents
// (UIApplication / NSWorkspace pick the app: Mail, Phone / FaceTime, Messages, Maps).

/**
 * Builds a `mailto:` URL (subject / body percent-encoded) from a feedback /
 * contact-us send intent. Shared by the navigation-stack interceptor and the
 * standalone "Contact us" sheet so both open mail the same way via the host's
 * open-url handler.
 */
internal fun NavigationIntent.NavigateToEmail.toMailtoUrl(): String {
    val params = buildList {
        subject?.let { add("subject=" + encodeQueryComponent(it)) }
        body?.let { add("body=" + encodeQueryComponent(it)) }
    }
    return "mailto:" + email + if (params.isNotEmpty()) "?" + params.joinToString("&") else ""
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
    return "https://maps.apple.com/?q=" + encodeQueryComponent(address)
}

// Spaces and brackets make the URL invalid, so they have to go.
private fun String.toDialString(): String = filter { it.isDigit() || it in "+*#,;" }

/**
 * Percent-encodes [value] for a URL query component (RFC 3986 unreserved set
 * preserved, everything else %-encoded over UTF-8). Implemented manually so the bridge
 * stays free of any platform URL API.
 */
private fun encodeQueryComponent(value: String): String = buildString {
    val hex = "0123456789ABCDEF"
    for (byte in value.encodeToByteArray()) {
        val c = byte.toInt() and 0xFF
        val unreserved = c in 'A'.code..'Z'.code ||
            c in 'a'.code..'z'.code ||
            c in '0'.code..'9'.code ||
            c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
        if (unreserved) {
            append(c.toChar())
        } else {
            append('%')
            append(hex[c shr HEX_NIBBLE_BITS])
            append(hex[c and 0x0F])
        }
    }
}

private const val HEX_NIBBLE_BITS = 4
