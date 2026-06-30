package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-platform RSA SSH-signing parity for the Apple ([AppleSshRsaSigner], Security
 * framework) signer.
 *
 * RSA PKCS#1 v1.5 is deterministic, so unlike Ed25519 the Apple output must be
 * byte-for-byte identical to the JVM (BouncyCastle) signer. The expected signatures were
 * generated independently with `openssl dgst -<hash> -sign`, and the JVM side asserts the
 * same values in `common/src/desktopTest/.../SshSigningParityTest.kt`. Keep these constants
 * byte-identical to the Kotlin/JVM and Swift tests.
 *
 * Both stored key formats are covered: PKCS#1 (what the app generates) and openssh-key-v1
 * (what Bitwarden stores) — the latter exercises the dP/dQ reconstruction.
 */
class SshRsaSignerAppleTest {
    @Test
    fun rsaSigningMatchesJvmForPkcs1Key() {
        assertRsaParity(RSA_PKCS1_PRIVATE_KEY_PEM)
    }

    @Test
    fun rsaSigningMatchesJvmForOpenSshKey() {
        assertRsaParity(RSA_OPENSSH_PRIVATE_KEY_PEM)
    }

    private fun assertRsaParity(privateKeyPem: String) {
        val cases = listOf(
            Triple(0, "ssh-rsa", RSA_SIG_SHA1_HEX),
            Triple(0x02, "rsa-sha2-256", RSA_SIG_SHA256_HEX),
            Triple(0x04, "rsa-sha2-512", RSA_SIG_SHA512_HEX),
        )
        for ((flags, expectedAlgorithm, expectedSigHex) in cases) {
            val result = AppleSshRsaSigner.sign(
                privateKeyPem = privateKeyPem,
                data = MESSAGE.encodeToByteArray(),
                flags = flags,
            )
            assertEquals(expectedAlgorithm, result.algorithm, "algorithm for flags=$flags")
            assertEquals(expectedSigHex, result.signature.toHex(), "signature for flags=$flags")
        }
    }

    private companion object {
        const val MESSAGE = "keyguard ssh signing parity vector"

        const val RSA_OPENSSH_PRIV_B64 =
            "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAABFwAAAAdzc2gtcnNhAAAA" +
            "AwEAAQAAAQEA439QWp+Lg09Kg0CnqNvYIMg37IVVIvMCqemfCoJbIluqX5oD8E3EQlNHN14+raDx" +
            "Jbl/xot7iFn5kYNpoXlV37GJJN8qKSo3ynTmBLiE2ZQvf5coJ5tuQYCjqf4UPleeYNo/YaXJzpwZ" +
            "V3EBStn+mcj3miqXtQ/IJm/YXF+DwcwBvzM3aNxcD6PnWT8jZRF1qE9er60yEbU62sNl3hRwfkLl" +
            "z4gAo5gfxabY0O4m/lkqRrzxDgp18kIktlTROzrrFmQYN7d1Z7GvAQKOzOdmSFHAXEczJ+97xkwg" +
            "lEqcdMA9QzIyB/Lz7WyLA8/pV1lpxYn5JfP8ekalrdEH+RsnPQAAA7i62SDDutkgwwAAAAdzc2gt" +
            "cnNhAAABAQDjf1Ban4uDT0qDQKeo29ggyDfshVUi8wKp6Z8KglsiW6pfmgPwTcRCU0c3Xj6toPEl" +
            "uX/Gi3uIWfmRg2mheVXfsYkk3yopKjfKdOYEuITZlC9/lygnm25BgKOp/hQ+V55g2j9hpcnOnBlX" +
            "cQFK2f6ZyPeaKpe1D8gmb9hcX4PBzAG/Mzdo3FwPo+dZPyNlEXWoT16vrTIRtTraw2XeFHB+QuXP" +
            "iACjmB/FptjQ7ib+WSpGvPEOCnXyQiS2VNE7OusWZBg3t3Vnsa8BAo7M52ZIUcBcRzMn73vGTCCU" +
            "Spx0wD1DMjIH8vPtbIsDz+lXWWnFifkl8/x6RqWt0Qf5Gyc9AAAAAwEAAQAAAP8rizLWJbOObp7U" +
            "gIuWxn2XyLrripnBFsJrw0utt0W6TdFOeyxF0py+FTKMZn2YvjVqAxdx6UC72HzsXTrarV7CcjjN" +
            "1ek799i156ofPTIwcyykP0pwSk/QOc8ZuoglZp/p6dw31IPMCBTRp8+XhCI3TUAXQg/HmD23HbFL" +
            "/Pw7G3TAHr5RCC/WHb7Qn1q4Xo3rvgtMrM7RAQJz1tzXE3E90LiQqDWIENmzPTkNOFNsmTsnsD2K" +
            "3lZ4V05AkB+mf30eVSwjQnxK9yEM7L7LBzKnsGn25cjYiW5cXXe4KY/XghO0RchhZNdN9XKayPJm" +
            "Yir7/tGFF5ENPJqbws9nV50AAACAQlZlbIwNurccmYhXspCpQKzEc4bJEflFZtjYT2VNYpvyBubz" +
            "+J1T3WsSJCfZjCKsjWlyiSfuYN72LlqZNJlcKpcqp33F2hYgQI84FnQY9wX5RoTu+TkmrWr/bDgm" +
            "x/tkv9orDxLazQSR15A11LcQXiuLQFhLg9BhbFPSc1+YS5IAAACBAPxp07PY8NNSqxhDyebah/ua" +
            "Jdv/klndy8AkMhucgFYLT2bh5XgEdrG3ZPAatgAWRINe8zB8DzkelXso3yKQ63H/XUxPAaZy8p8/" +
            "ilmjA6yfYldyng2+CrRJhCbqU/fjr/EPhIg1YdBAsoJE2f8SFLCm+eQ3DSrr79wNyESnb0qLAAAA" +
            "gQDmutpSQrniPgA37vb21R+Nb+CM6wqcKnI5onWFZniBR88e5m939oKzMlxvB39Sj6i9FzqgBFnn" +
            "d0/LBk8ltrx13n1woojBnsyH1hYCZIr0yN9gRhLyglZFaPKq2uq5cVndYN9dRJU07HdpEVZMkejw" +
            "qsVbYk7bQP/FNaNPhR62VwAAAAABAgME"

        const val RSA_PKCS1_PRIV_B64 =
            "MIIEoAIBAAKCAQEA439QWp+Lg09Kg0CnqNvYIMg37IVVIvMCqemfCoJbIluqX5oD8E3EQlNHN14+" +
            "raDxJbl/xot7iFn5kYNpoXlV37GJJN8qKSo3ynTmBLiE2ZQvf5coJ5tuQYCjqf4UPleeYNo/YaXJ" +
            "zpwZV3EBStn+mcj3miqXtQ/IJm/YXF+DwcwBvzM3aNxcD6PnWT8jZRF1qE9er60yEbU62sNl3hRw" +
            "fkLlz4gAo5gfxabY0O4m/lkqRrzxDgp18kIktlTROzrrFmQYN7d1Z7GvAQKOzOdmSFHAXEczJ+97" +
            "xkwglEqcdMA9QzIyB/Lz7WyLA8/pV1lpxYn5JfP8ekalrdEH+RsnPQIDAQABAoH/K4sy1iWzjm6e" +
            "1ICLlsZ9l8i664qZwRbCa8NLrbdFuk3RTnssRdKcvhUyjGZ9mL41agMXcelAu9h87F062q1ewnI4" +
            "zdXpO/fYteeqHz0yMHMspD9KcEpP0DnPGbqIJWaf6encN9SDzAgU0afPl4QiN01AF0IPx5g9tx2x" +
            "S/z8Oxt0wB6+UQgv1h2+0J9auF6N674LTKzO0QECc9bc1xNxPdC4kKg1iBDZsz05DThTbJk7J7A9" +
            "it5WeFdOQJAfpn99HlUsI0J8SvchDOy+ywcyp7Bp9uXI2IluXF13uCmP14ITtEXIYWTXTfVymsjy" +
            "ZmIq+/7RhReRDTyam8LPZ1edAoGBAPxp07PY8NNSqxhDyebah/uaJdv/klndy8AkMhucgFYLT2bh" +
            "5XgEdrG3ZPAatgAWRINe8zB8DzkelXso3yKQ63H/XUxPAaZy8p8/ilmjA6yfYldyng2+CrRJhCbq" +
            "U/fjr/EPhIg1YdBAsoJE2f8SFLCm+eQ3DSrr79wNyESnb0qLAoGBAOa62lJCueI+ADfu9vbVH41v" +
            "4IzrCpwqcjmidYVmeIFHzx7mb3f2grMyXG8Hf1KPqL0XOqAEWed3T8sGTyW2vHXefXCiiMGezIfW" +
            "FgJkivTI32BGEvKCVkVo8qra6rlxWd1g311ElTTsd2kRVkyR6PCqxVtiTttA/8U1o0+FHrZXAoGA" +
            "cZFv9jiCyqIkZyuA+UST8Gl/5UdNYu+/T/k5LmExAQXfuOukomZnvThFyx5Jiyp5sx4Sj1sSQmH3" +
            "Yc2W7+eCd+0s3juiA3EupuYg6onFCnM//RaN9KLwEAQP2K6QDzW7oNuZhut10gx6BNDTwMcy1fSa" +
            "9MqsOwajCkCNPg6TrVcCgYBW3vVCUZMxJozoglWDu9yEYSIXjItTHvwgzRUYY+ttKX9HMBW6AhtV" +
            "otmAiD8c86/hefR9uQWsi5SEaVy1Y2bzrhBmrxt0Yvi5EBb7UaWr4lGQKvIJUp9uxMPRJlmH9rCS" +
            "FF4k/KR856dR7NAkRi+BJZrm/E2aaY+jTb2uO/3DQwKBgEJWZWyMDbq3HJmIV7KQqUCsxHOGyRH5" +
            "RWbY2E9lTWKb8gbm8/idU91rEiQn2YwirI1pcokn7mDe9i5amTSZXCqXKqd9xdoWIECPOBZ0GPcF" +
            "+UaE7vk5Jq1q/2w4Jsf7ZL/aKw8S2s0EkdeQNdS3EF4ri0BYS4PQYWxT0nNfmEuS"

        val RSA_PKCS1_PRIVATE_KEY_PEM =
            "-----BEGIN RSA PRIVATE KEY-----\n$RSA_PKCS1_PRIV_B64\n-----END RSA PRIVATE KEY-----\n"

        val RSA_OPENSSH_PRIVATE_KEY_PEM =
            "-----BEGIN OPENSSH PRIVATE KEY-----\n$RSA_OPENSSH_PRIV_B64\n-----END OPENSSH PRIVATE KEY-----\n"

        const val RSA_SIG_SHA1_HEX =
            "cebe94529f9fe33c2a20d72a91abb1bf0366ff647233b1954b650a1d880c47446597c2674ca1" +
            "a95c2c934d937e7c04df2ddda0f51c0d86d5ea91de00f23dd61b5f6c0f601bf447dc46703186" +
            "7d1d5089a763d68710f8a12f4bae54ce05da70f8973d874e63b47660075522d23defbaad3635" +
            "c4626ad29e4e796411c8cbb4c4f73d901cc4cb7ae6d5d3852efbc5853ae2c91e8f50a52a4cc4" +
            "71bc564d08df7975df72d34451cc13a616da44f612a1d48b9e97f5d904bce27bec464c2d199d" +
            "f8bbb12e74001cba085a0ae6ac3171f116a2f2d419e7d4d97406331966268851d9c39b46c16c" +
            "9fd2f725f11a31c9e0aa8bedbe785007f93808c8ba9107a2b3c3e034"

        const val RSA_SIG_SHA256_HEX =
            "77b339ba6ca43daed47a7ae98d97c58993c13497d58bad1add717e0f21963e49e876dd17a88d" +
            "6c235d79c66aaabc6eb763acbb1b4f8f40872137a85c73f75dcc4bfa7719362054aba988c843" +
            "c299dd987caf3ce0fd656c8e8eb0e6bd0609781ffd335fc26d3e52736166aa9a323f667d0949" +
            "30c4c904660297e9203b2f0be21a4b91f1fa13010f1b1a8cd2f574cc9df6be7d568d032029b7" +
            "4feef0eb1632d9dfbc77045451e870235392b67f88ffc0dec716afee694a19d359d96a8e7027" +
            "fc5a95002ec8015b973445b59205aa9b80e9e36b3562448f25c169ebd93bb1f9915c7f267825" +
            "abadd0855306c0f328f468a8e3fd3243f86c3edcbcdebadb6ceac027"

        const val RSA_SIG_SHA512_HEX =
            "9ee2205cebf82eb2273ff9160a14e08561b0e7df77eee59a3d0c827b972cb7a0fd9669beae9e" +
            "750ce85f52872c45304a315ffd8f92835712bf8c8778333a5dd87cea0df18bfec9922d3dc0f4" +
            "f5131dd5218b3810db238461a1563df4c514256afe224d36d37509af89573f150c38eb6c8c65" +
            "5f8a78ac09b7fc7e3099a7ac16bfd25b77d0ef9d4bcaeacc94fb8ae6e4ef71596179f6ac3475" +
            "f74c0277536053287060670ad34bf653e4f13ff7e003a827b2d19c4ccdee0f5def79c5eda62b" +
            "4f4dfe39a5e5a722eb6aa097ad04fddb588ff9fb56f5620ed39b9256f5f2febe0b8b34f7ce8a" +
            "15029e19bd876b1044b9ab52bcd32af22b1511973730c7bd142694cf"
    }
}
