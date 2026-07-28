package app.keemobile.kotpass.resources

internal object Salsa20Res {
    val SalsaTestCases = listOf(
        StreamCipherEncryptionTestCase(
            rounds = 12,
            key = "80000000000000000000000000000000",
            iv = "0000000000000000",
            plaintext = "0000000000000000",
            cipher = "fc207dbfc76c5e17"
        ),
        StreamCipherEncryptionTestCase(
            rounds = 12,
            key = "00400000000000000000000000000000",
            iv = "0000000000000000",
            plaintext = "0000000000000000",
            cipher = "6c11a3f95fec7f48"
        ),
        StreamCipherEncryptionTestCase(
            rounds = 12,
            key = "09090909090909090909090909090909",
            iv = "0000000000000000",
            plaintext = "0000000000000000",
            cipher = "78e11fc333dede88"
        ),
        StreamCipherEncryptionTestCase(
            rounds = 12,
            key = "1B1B1B1B1B1B1B1B1B1B1B1B1B1B1B1B",
            iv = "0000000000000000",
            plaintext = "0000000000000000",
            cipher = "a67474611df551ff"
        ),
        StreamCipherEncryptionTestCase(
            rounds = 12,
            key = "8000000000000000000000000000000000000000000000000000000000000000",
            iv = "0000000000000000",
            plaintext = "0000000000000000",
            cipher = "afe411ed1c4e07e4"
        ),
        StreamCipherEncryptionTestCase(
            rounds = 12,
            key = "0053A6F94C9FF24598EB3E91E4378ADD3083D6297CCF2275C81B6EC11467BA0D",
            iv = "0D74DB42A91077DE",
            plaintext = "0000000000000000",
            cipher = "52e20cf8775ae882"
        ),
        StreamCipherEncryptionTestCase(
            rounds = 20,
            key = "80000000000000000000000000000000",
            iv = "0000000000000000",
            plaintext = "0000000000000000",
            cipher = "4dfa5e481da23ea0"
        ),
        StreamCipherEncryptionTestCase(
            rounds = 20,
            key = "00000000000000000000000000000000",
            iv = "8000000000000000",
            plaintext = "0000000000000000",
            cipher = "b66c1e4446dd9557"
        ),
        StreamCipherEncryptionTestCase(
            rounds = 20,
            key = "0053A6F94C9FF24598EB3E91E4378ADD",
            iv = "0D74DB42A91077DE",
            plaintext = "0000000000000000",
            cipher = "05e1e7beb697d999"
        ),
        StreamCipherEncryptionTestCase(
            rounds = 20,
            key = "8000000000000000000000000000000000000000000000000000000000000000",
            iv = "0000000000000000",
            plaintext = "0000000000000000",
            cipher = "e3be8fdd8beca2e3"
        ),
        StreamCipherEncryptionTestCase(
            rounds = 20,
            key = "0000000000000000000000000000000000000000000000000000000000000000",
            iv = "8000000000000000",
            plaintext = "0000000000000000",
            cipher = "2aba3dc45b494700"
        ),
        StreamCipherEncryptionTestCase(
            rounds = 20,
            key = "0053A6F94C9FF24598EB3E91E4378ADD3083D6297CCF2275C81B6EC11467BA0D",
            iv = "0D74DB42A91077DE",
            plaintext = "0000000000000000",
            cipher = "f5fad53f79f9df58"
        )
    )
}
