package com.artemchep.keyguard.common.service.crypto

/**
 * Shared armored secret-key fixtures for the GPG tests.
 *
 * These are real, unprotected (empty-passphrase) OpenPGP secret keys exported by
 * GnuPG. They are checked in verbatim (not regenerated on the fly) because the
 * golden keygrips asserted in [GpgKeygripCalculatorJvmTest] are computed by
 * `gpg --with-keygrip` from these exact bytes — regenerating the keys would break
 * that binding.
 */
object GpgTestKeyFixtures {
    // Real, unprotected (empty-passphrase) RSA-2048 key with an RSA encryption
    // subkey, generated with gpg --quick-gen-key + --quick-add-key ... rsa2048 encr.
    val RSA = """
        -----BEGIN PGP PRIVATE KEY BLOCK-----

        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
        QQ==
        =wJkM
        -----END PGP PRIVATE KEY BLOCK-----
    """.trimIndent()

    // Real, unprotected (empty-passphrase) Ed25519 primary with a Curve25519
    // (cv25519, algorithm 18) encryption subkey.
    val CV25519 = """
        -----BEGIN PGP PRIVATE KEY BLOCK-----

        lFgEaj9rzxYJKwYBBAHaRw8BAQdAbF/WEPrIP6KKXMDvdC38qJefWOzgPjl1oRjO
        Zq0b1Q4AAP416BYYjfvazxmhBWie0YPQHmRv5DtZABE+5Eo8vsGC8BB2tCxLZXln
        dWFyZCBUZXN0IENWMjU1MTkgPGN2MjU1MTlAdGVzdC5pbnZhbGlkPoivBBMWCgBX
        FiEE0LvPuyUNO7BljlOE+D2UfSnv7PcFAmo/a88bFIAAAAAABAAObWFudTIsMi41
        KzEuMTIsMCwzAhsDBQsJCAcCAiICBhUKCQgLAgQWAgMBAh4HAheAAAoJEPg9lH0p
        7+z3szkA/iTKzuwQ/a33NXIiGaEluTQsPTfvLZFPHzsSrHRUPtAxAP4me3t1tgkV
        BrbfFEx8MwS2TpYJ+TseDv+Pf+vwp/doBJxdBGo/a+wSCisGAQQBl1UBBQEBB0Bc
        0xWVtzx07/KrLcmPAncTB+02SZ5KSLrZ4UXO8bp9dgMBCAcAAP934N+JD9z0Gkm1
        ZSVtLdTx8gIrDriwen2vkSJLUzL+UBCqiJQEGBYKADwWIQTQu8+7JQ07sGWOU4T4
        PZR9Ke/s9wUCaj9r7BsUgAAAAAAEAA5tYW51MiwyLjUrMS4xMiwwLDMCGwwACgkQ
        +D2UfSnv7PculAD/T22Upu3v6Pbqn5DBsKxu7yiu4LFs1jjnbbp7LLpDFL0BALpz
        Bc+fU17BLteMYYp5rXgKCOm+qy1Z70+LJ8ljtz4I
        =s3tp
        -----END PGP PRIVATE KEY BLOCK-----
    """.trimIndent()

    // Real, unprotected (empty-passphrase) NIST P-256 cert primary with a NIST
    // P-256 (algorithm 18, ECDH) encryption subkey.
    val NISTP256 = """
        -----BEGIN PGP PRIVATE KEY BLOCK-----

        lHcEaj9rzxMIKoZIzj0DAQcCAwQfcmhUf8LvMYcLaAHkZSMtJB9+dqIxDLdUtmpu
        hxVUnShusAWXyYU0J/hOCsA9d5eTmH76szMbgR63XIrAoIonAAEA5YAaB5Leuw/L
        CwK0GlQSFLIiCSnMbp0N9ROrQSxapo8MerQuS2V5Z3VhcmQgVGVzdCBOSVNUUDI1
        NiA8bmlzdHAyNTZAdGVzdC5pbnZhbGlkPoivBBMTCABXFiEE15aXUTWUsJy/vqpO
        OWbQuiMyVbYFAmo/a88bFIAAAAAABAAObWFudTIsMi41KzEuMTIsMCwzAhsBBQsJ
        CAcCAiICBhUKCQgLAgQWAgMBAh4HAheAAAoJEDlm0LojMlW2q1MBAO1FQJZh5sRx
        oO96cHKxZbokreWB524RrNzpRplNUX4oAP9d7vPTvnAXH6NC8PWRlUgutqb2Di+v
        C6T/BXd8z6cjzJx7BGo/a9oSCCqGSM49AwEHAgMEEYPi8/W0SKKk8pKiQT3v5taQ
        HeY2hCxqngJKz8CBeHAYWT77ASqOV5tONcJ8zKhayVK3p9sm4sfTpqq4cOhcmQMB
        CAcAAP0bSU+3IDxiHrtc3j4E5R27NUpOO0eh67HEY6LHfqay0Q/6iJQEGBMIADwW
        IQTXlpdRNZSwnL++qk45ZtC6IzJVtgUCaj9r2hsUgAAAAAAEAA5tYW51MiwyLjUr
        MS4xMiwwLDMCGwwACgkQOWbQuiMyVbYBQgEA9EoFYK4/vbVmNBaLx2FohvTbshAl
        m7mjRMf4SMnt9KAA/370zYdGavyQzXn1a/NCIqdhmttX9bD7QRdX1tWcjdqG
        =JfOd
        -----END PGP PRIVATE KEY BLOCK-----
    """.trimIndent()

    // Real, unprotected (empty-passphrase) Ed25519 (algorithm 22, EdDSA legacy)
    // signing key generated with `gpg --quick-gen-key ... ed25519 sign`.
    val ED25519 = """
        -----BEGIN PGP PRIVATE KEY BLOCK-----

        lFgEaj9lLhYJKwYBBAHaRw8BAQdAxTPxq2XYMrAEG35eXF5saqexR+9LGUg0U4O1
        UXlET3sAAQDbNbOQqLZWIfRbniG5U8csKAPT33lx54n/P5IMS/fAXRCotCxLZXln
        dWFyZCBUZXN0IEVkMjU1MTkgPGVkMjU1MTlAdGVzdC5pbnZhbGlkPoivBBMWCgBX
        FiEEDOQbxnhOfUAP6e1DvWc3BI8r8Y8FAmo/ZS4bFIAAAAAABAAObWFudTIsMi41
        KzEuMTIsMCwzAhsDBQsJCAcCAiICBhUKCQgLAgQWAgMBAh4HAheAAAoJEL1nNwSP
        K/GP4A4A/0PqgUXDinjfr/+XDlSw+kzqz9Orkxdx0BxReUXP4sW7AQCK4wwITcBe
        GbPK5Vwfbqd29bJ9nXosxKsWoLQ8kqetDg==
        =2xS2
        -----END PGP PRIVATE KEY BLOCK-----
    """.trimIndent()

    // Real, unprotected (empty-passphrase) ECDSA NIST P-256 (algorithm 19)
    // signing key generated with `gpg --quick-gen-key ... nistp256 sign`.
    val ECDSA = """
        -----BEGIN PGP PRIVATE KEY BLOCK-----

        lHcEaj9lLhMIKoZIzj0DAQcCAwQ151mVjwYSVX7wHnh6+xbFJ1UkSegucMicd0+j
        8ixVLSXIoxPQslymX1zlscL3F2hbqOenOQrNHBKxxtcVULfRAAEAkaNdIuM+6PUQ
        bSL4OtoXM4vxt6nrj5oHL39uKP+JcJ4QeLQoS2V5Z3VhcmQgVGVzdCBFQ0RTQSA8
        ZWNkc2FAdGVzdC5pbnZhbGlkPoivBBMTCABXFiEEC70KRO2Gk88VCf4kweZN9QVb
        ubcFAmo/ZS4bFIAAAAAABAAObWFudTIsMi41KzEuMTIsMCwzAhsDBQsJCAcCAiIC
        BhUKCQgLAgQWAgMBAh4HAheAAAoJEMHmTfUFW7m30KAA/2qw53Bzp7vvQirbRLzE
        TfoXv7yCRwJEukw6tJ6WlW0UAQD9apb2BSW0IHhS93aCGi7PGgifulu+aGChn0n1
        D7GZvg==
        =JVxf
        -----END PGP PRIVATE KEY BLOCK-----
    """.trimIndent()
}
