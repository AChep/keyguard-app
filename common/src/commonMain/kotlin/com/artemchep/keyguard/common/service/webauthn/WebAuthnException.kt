package com.artemchep.keyguard.common.service.webauthn

sealed class WebAuthnException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class WebAuthnEncodingException(
    message: String,
    cause: Throwable? = null,
) : WebAuthnException(message, cause)

class WebAuthnInvalidStateException(
    message: String,
    cause: Throwable? = null,
) : WebAuthnException(message, cause)

class WebAuthnNotAllowedException(
    message: String,
    cause: Throwable? = null,
) : WebAuthnException(message, cause)

