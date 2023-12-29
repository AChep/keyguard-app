package com.artemchep.keyguard.desktop.util

import io.ktor.http.encodeURLParameter
import java.awt.Desktop
import java.net.URI

fun navigateToEmail(
    email: String,
    subject: String? = null,
    body: String? = null,
) {
    val uri = buildString {
        var isFirstQueryParam = true

        fun addQueryParam(
            key: String,
            value: String,
        ) {
            if (isFirstQueryParam) {
                isFirstQueryParam = false
                append("?")
            } else {
                append("&")
            }
            append(key)
            append("=")
            append(value.encodeURLParameter())
        }

        append("mailto:")
        append(email)
        // append query parameters
        if (subject != null) addQueryParam("subject", subject)
        if (body != null) addQueryParam("body", body)
    }.let(::URI)
    Desktop.getDesktop().mail(uri)
}
