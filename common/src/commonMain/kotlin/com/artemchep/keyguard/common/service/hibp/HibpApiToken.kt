package com.artemchep.keyguard.common.service.hibp

/** The length of a Have I Been Pwned API token, in hexadecimal characters. */
const val HIBP_API_TOKEN_LENGTH = 32

val HIBP_API_TOKEN_REGEX = Regex("^[0-9a-fA-F]{$HIBP_API_TOKEN_LENGTH}$")
