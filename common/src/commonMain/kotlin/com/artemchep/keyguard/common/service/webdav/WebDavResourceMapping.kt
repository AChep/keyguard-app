package com.artemchep.keyguard.common.service.webdav

import com.artemchep.keyguard.util.webdav.WebDavResource

val WebDavResource.isFileResource: Boolean
    get() = !isCollection

fun WebDavResource.takeFileResourceOrNull(): WebDavResource? =
    takeIf { resource -> resource.isFileResource }
