package com.artemchep.keyguard.feature.dragout.model

import com.artemchep.keyguard.common.model.DSecret
import io.ktor.http.Url

/**
 * The link a URI row offers by drag: an absolute http(s) URL without embedded
 * credentials. A regular-expression match is a pattern, not a link.
 */
fun draggableUrl(
    url: Url,
    match: DSecret.Uri.MatchType?,
    capabilities: DragOutCapabilities = dragOutCapabilities,
): String? = url.toString().takeIf {
    capabilities.urls &&
        match != DSecret.Uri.MatchType.RegularExpression &&
        url.protocol.name.lowercase() in DRAGGABLE_SCHEMES &&
        url.user.isNullOrEmpty() &&
        url.password.isNullOrEmpty()
}

private val DRAGGABLE_SCHEMES = setOf("http", "https")
