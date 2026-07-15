package com.artemchep.keyguard.crypto

internal class GpgUnsupportedKeyVersionException(
    val version: Int,
) : IllegalArgumentException("OpenPGP V2/V3 keys are not supported.")
