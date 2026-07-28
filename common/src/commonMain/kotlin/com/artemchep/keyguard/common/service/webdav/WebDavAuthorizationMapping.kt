package com.artemchep.keyguard.common.service.webdav

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.model.WebDavCredentials
import com.artemchep.keyguard.common.model.WebDavLocation
import com.artemchep.keyguard.util.webdav.WebDavAuthorization

fun webDavAuthorizationOf(
    username: String?,
    password: Password?,
): WebDavAuthorization? = WebDavCredentials.of(
    username = username,
    password = password,
)?.toWebDavAuthorization()

fun WebDavCredentials.toWebDavAuthorization(
): WebDavAuthorization = WebDavAuthorization.Basic(
    username = username,
    password = password?.value.orEmpty(),
)

fun WebDavLocation.toWebDavAuthorization(
): WebDavAuthorization? = credentials
    ?.toWebDavAuthorization()
