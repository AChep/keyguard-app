package com.artemchep.keyguard.util.fido2

import java.text.Normalizer

internal actual fun normalizeFido2Pin(pin: String): String =
    Normalizer.normalize(pin, Normalizer.Form.NFC)
