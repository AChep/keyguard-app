package com.artemchep.keyguard.util.fido2

/** CTAP PINs use NFC before UTF-8 length validation and authentication. */
internal expect fun normalizeFido2Pin(pin: String): String
