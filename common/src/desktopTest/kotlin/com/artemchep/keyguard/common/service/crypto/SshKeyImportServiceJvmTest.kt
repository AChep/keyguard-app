package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.crypto.SshKeyImportServiceJvm
import com.artemchep.keyguard.crypto.KeyPairGeneratorJvm
import net.schmizz.sshj.common.Buffer.PlainBuffer
import org.bouncycastle.util.encoders.Base64
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SshKeyImportServiceJvmTest {
    private val keyPairGenerator = KeyPairGeneratorJvm(
        cryptoGenerator = CryptoGeneratorJvm(),
    )
    private val service = SshKeyImportServiceJvm(
        cryptoGenerator = CryptoGeneratorJvm(),
    )

    @Test
    fun `imports unencrypted OpenSSH Ed25519 private key`() {
        assertImported(
            request = SshKeyImportRequest(
                content = OPENSSH_ED25519,
                fileName = "id_ed25519",
                passphrase = null,
            ),
            type = com.artemchep.keyguard.common.model.KeyPair.Type.ED25519,
        )
    }

    @Test
    fun `imports unencrypted PEM PKCS1 RSA private key`() {
        assertImported(
            request = SshKeyImportRequest(
                content = PEM_PKCS1_RSA,
                fileName = "id_rsa",
                passphrase = null,
            ),
            type = com.artemchep.keyguard.common.model.KeyPair.Type.RSA,
        )
    }

    @Test
    fun `imports unencrypted PKCS8 RSA private key`() {
        assertImported(
            request = SshKeyImportRequest(
                content = PKCS8_RSA,
                fileName = "id_rsa.pk8",
                passphrase = null,
            ),
            type = com.artemchep.keyguard.common.model.KeyPair.Type.RSA,
        )
    }

    @Test
    fun `imported PKCS8 RSA key keeps canonical private pem after repopulate`() {
        val imported = service.`import`(
            SshKeyImportRequest(
                content = PKCS8_RSA,
                fileName = "id_rsa.pk8",
                passphrase = null,
            ),
        )
        val keyPair = assertIs<SshKeyImportResult.Success>(imported).keyPair

        val repopulated = keyPairGenerator.populate(
            keyPairGenerator.parse(
                privateKey = keyPair.privateKey.ssh,
                publicKey = keyPair.publicKey.ssh,
            ),
        )

        assertEquals(keyPair.privateKey.ssh, repopulated.privateKey.ssh)
    }

    @Test
    fun `encrypted OpenSSH key requests a passphrase first`() {
        assertEquals(
            SshKeyImportResult.NeedsPassphrase("OpenSSH"),
            service.`import`(
                SshKeyImportRequest(
                    content = OPENSSH_ED25519_ENCRYPTED,
                    fileName = "id_ed25519",
                    passphrase = null,
                ),
            ),
        )
    }

    @Test
    fun `encrypted OpenSSH key imports with correct passphrase`() {
        assertImported(
            request = SshKeyImportRequest(
                content = OPENSSH_ED25519_ENCRYPTED,
                fileName = "id_ed25519",
                passphrase = "foobar",
            ),
            type = com.artemchep.keyguard.common.model.KeyPair.Type.ED25519,
        )
    }

    @Test
    fun `encrypted OpenSSH key rejects wrong passphrase`() {
        assertEquals(
            SshKeyImportResult.Error(SshKeyImportError.InvalidPassphrase),
            service.`import`(
                SshKeyImportRequest(
                    content = OPENSSH_ED25519_ENCRYPTED,
                    fileName = "id_ed25519",
                    passphrase = "incorrect",
                ),
            ),
        )
    }

    @Test
    fun `encrypted PEM key requests a passphrase first`() {
        assertEquals(
            SshKeyImportResult.NeedsPassphrase("PEM"),
            service.`import`(
                SshKeyImportRequest(
                    content = PEM_PKCS1_RSA_ENCRYPTED,
                    fileName = "id_rsa",
                    passphrase = null,
                ),
            ),
        )
    }

    @Test
    fun `encrypted PEM key imports with correct passphrase`() {
        assertImported(
            request = SshKeyImportRequest(
                content = PEM_PKCS1_RSA_ENCRYPTED,
                fileName = "id_rsa",
                passphrase = "passphrase",
            ),
            type = com.artemchep.keyguard.common.model.KeyPair.Type.RSA,
        )
    }

    @Test
    fun `encrypted PEM key rejects wrong passphrase`() {
        assertEquals(
            SshKeyImportResult.Error(SshKeyImportError.InvalidPassphrase),
            service.`import`(
                SshKeyImportRequest(
                    content = PEM_PKCS1_RSA_ENCRYPTED,
                    fileName = "id_rsa",
                    passphrase = "incorrect",
                ),
            ),
        )
    }

    @Test
    fun `imports unencrypted PuTTY Ed25519 private key`() {
        assertImported(
            request = SshKeyImportRequest(
                content = PUTTY_ED25519,
                fileName = "id_ed25519.ppk",
                passphrase = null,
            ),
            type = com.artemchep.keyguard.common.model.KeyPair.Type.ED25519,
        )
    }

    @Test
    fun `imports unencrypted PuTTY RSA private key`() {
        assertImported(
            request = SshKeyImportRequest(
                content = toPuTTYRsaPrivateKey(generateRsaKeyPair()),
                fileName = "id_rsa.ppk",
                passphrase = null,
            ),
            type = com.artemchep.keyguard.common.model.KeyPair.Type.RSA,
        )
    }

    @Test
    fun `encrypted PuTTY key requests a passphrase first`() {
        assertEquals(
            SshKeyImportResult.NeedsPassphrase("PuTTY"),
            service.`import`(
                SshKeyImportRequest(
                    content = PUTTY_ED25519_ENCRYPTED,
                    fileName = "id_ed25519.ppk",
                    passphrase = null,
                ),
            ),
        )
    }

    @Test
    fun `encrypted PuTTY key imports with correct passphrase`() {
        assertImported(
            request = SshKeyImportRequest(
                content = PUTTY_ED25519_ENCRYPTED,
                fileName = "id_ed25519.ppk",
                passphrase = "123456",
            ),
            type = com.artemchep.keyguard.common.model.KeyPair.Type.ED25519,
        )
    }

    @Test
    fun `encrypted PuTTY key rejects wrong passphrase`() {
        assertEquals(
            SshKeyImportResult.Error(SshKeyImportError.InvalidPassphrase),
            service.`import`(
                SshKeyImportRequest(
                    content = PUTTY_ED25519_ENCRYPTED,
                    fileName = "id_ed25519.ppk",
                    passphrase = "incorrect",
                ),
            ),
        )
    }

    @Test
    fun `rejects unsupported algorithm in a supported container`() {
        assertEquals(
            SshKeyImportResult.Error(SshKeyImportError.UnsupportedAlgorithm),
            service.`import`(
                SshKeyImportRequest(
                    content = PKCS8_ECDSA,
                    fileName = "id_ecdsa",
                    passphrase = null,
                ),
            ),
        )
    }

    @Test
    fun `rejects malformed key material`() {
        assertEquals(
            SshKeyImportResult.Error(SshKeyImportError.MalformedKey),
            service.`import`(
                SshKeyImportRequest(
                    content = """
                    -----BEGIN RSA PRIVATE KEY-----
                    not-base64
                    -----END RSA PRIVATE KEY-----
                """.trimIndent(),
                    fileName = "broken_rsa",
                    passphrase = null,
                ),
            ),
        )
    }

    @Test
    fun `rejects unsupported format`() {
        assertEquals(
            SshKeyImportResult.Error(SshKeyImportError.UnsupportedFormat),
            service.`import`(
                SshKeyImportRequest(
                    content = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleOnly",
                    fileName = "id_ed25519.pub",
                    passphrase = null,
                ),
            ),
        )
    }

    @Test
    fun `generated SSH keys round-trip through import`() {
        val generated = keyPairGenerator.populate(
            keyPairGenerator.ed25519(),
        )

        val result = service.`import`(
            SshKeyImportRequest(
                content = generated.privateKey.ssh,
                fileName = "generated_ed25519",
                passphrase = null,
            ),
        )
        val imported = assertIs<SshKeyImportResult.Success>(result).keyPair

        assertEquals(generated.type, imported.type)
        assertEquals(generated.publicKey.ssh, imported.publicKey.ssh)
        assertEquals(generated.publicKey.fingerprint, imported.publicKey.fingerprint)
    }

    private fun assertImported(
        request: SshKeyImportRequest,
        type: com.artemchep.keyguard.common.model.KeyPair.Type,
    ) {
        val result = service.`import`(request)
        val success = assertIs<SshKeyImportResult.Success>(result)
        assertEquals(type, success.keyPair.type)
        assertTrue(success.keyPair.publicKey.ssh.startsWith("ssh-"))
        assertTrue(success.keyPair.publicKey.fingerprint.startsWith("SHA256:"))
        assertTrue(success.keyPair.privateKey.ssh.contains("PRIVATE KEY"))
    }

    private fun generateRsaKeyPair(): java.security.KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048, SecureRandom())
        return keyPairGenerator.generateKeyPair()
    }

    private fun toPuTTYRsaPrivateKey(keyPair: java.security.KeyPair): String {
        val publicKey = keyPair.public as RSAPublicKey
        val privateKey = keyPair.private as RSAPrivateCrtKey

        val publicPayload = PlainBuffer()
            .putString("ssh-rsa")
            .putMPInt(publicKey.publicExponent)
            .putMPInt(publicKey.modulus)
            .getCompactData()
        val privatePayload = PlainBuffer()
            .putMPInt(privateKey.privateExponent)
            .getCompactData()

        val publicBase64 = Base64.toBase64String(publicPayload)
        val privateBase64 = Base64.toBase64String(privatePayload)

        return buildString {
            appendLine("PuTTY-User-Key-File-2: ssh-rsa")
            appendLine("Encryption: none")
            appendLine("Comment: keyguard-test-rsa")
            appendLine("Public-Lines: ${publicBase64.chunked(70).size}")
            publicBase64.chunked(70).forEach { appendLine(it) }
            appendLine("Private-Lines: ${privateBase64.chunked(70).size}")
            privateBase64.chunked(70).forEach { appendLine(it) }
        }
    }

    private companion object {
        private val OPENSSH_ED25519 = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
            QyNTUxOQAAACAwHSYkZJATPMgvLHkxKAJ9j38Gyyq5HGoWdMcT6FiAiQAAAJDimgR84poE
            fAAAAAtzc2gtZWQyNTUxOQAAACAwHSYkZJATPMgvLHkxKAJ9j38Gyyq5HGoWdMcT6FiAiQ
            AAAECmsckQycWnfGQK6XtQpaMGODbAkMQOdJNK6XJSipB7dDAdJiRkkBM8yC8seTEoAn2P
            fwbLKrkcahZ0xxPoWICJAAAACXJvb3RAc3NoagECAwQ=
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()

        private val OPENSSH_ED25519_ENCRYPTED = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jYmMAAAAGYmNyeXB0AAAAGAAAABBLQVXV9f
            Wpw8AL9RTpAr//AAAAEAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAAIJ8ww4hJG/gHJYdk
            jTTBDF1GNz+228nuWprPV+NbQauAAAAAoGHEO7x3fSRBohvrIR52U4XD3uqRnhrPYm01k1
            f4HHNNv46m92Zw6JKIB9Trrvp0sdMI8MVb79bN45rbn6mvpABtWl6T5TOTyMnKzDfAOx9c
            FTaasWFmgtgkXOsu5pLrYBAQgCHWbzjjz6KoV1DmD4SAn9Ojf9Oh+YdAEKZcsvklgpu+Kj
            nzN/DR0jt7Nzep2kNCLAS24QEkvQeATVSDiL8=
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()

        private val PEM_PKCS1_RSA = """
            -----BEGIN RSA PRIVATE KEY-----
            MIICXQIBAAKBgQCm2IJ9gWDkPTlQ37NNUB0za5mCsQ8bi++8fyEqw7wl8ZNBh3qt
            TcnL+m+NZfQjUC0BXic7PcMLVm4A3ID2IAZQM+axfq9aL4huWerm4ua6tvdt4gQK
            oL1+8JFmdFvFw5pWW/NZHtkIprbVf7KtYrU27WmMhXruN071UzqLsw08cwIDAQAB
            AoGAHQ7cOyuLSnT3RISRX8eyLkBxLffUX8HRcQzbI+2PGTSnpuQHk6NWn/Xv87pr
            +LKABBr3zjOFgrX81p2QwEz3jDxNXzbOeZzhuvGXCX5GocuEO4n5EhDvXRDF4uht
            uvVV5FsQv/sTOR0PNo1nELiAA8k3NYDxraB83q7wtsmErtECQQDYWMnq8mwRe49d
            jIXNKJeNiuLUYxO3CLI/vx279gDKlKrt677trr1e7JZqm/DapEWG511tw3cW63gQ
            +qxtgkw1AkEAxW0UeaNaJd7DApqwGAcS1JkygCKwzQ4ns/Co15qUgMkqCkmQU9AU
            /zQpt2+BjdYVe50r/nr8K1KYwrBsyndrBwJBALe90N+FvFqswfoFmq2/R9eimTsg
            WmIdNKYHPs2gBNQIp5MhoSpkOdkgvi8U+d33nkUQwryyQbZpjbN98mufOfECQEML
            eBiW0NZrf+4yefqu7EYmgG/jWAdK91C0OaJ+bFAQAKbdtJXB5F+GZ2RUCbsRKNqB
            1Z7mRRyxQA9dupRHWaECQQCM9bbCtfGesgvZlhBavlWavu8iCvJlAbGdf5QMlFQE
            kABmZg84Fy3NUFCD+RXCuatb4Oo9P/WPIbjYiC4p0hLJ
            -----END RSA PRIVATE KEY-----
        """.trimIndent()

        private val PEM_PKCS1_RSA_ENCRYPTED = """
            -----BEGIN RSA PRIVATE KEY-----
            Proc-Type: 4,ENCRYPTED
            DEK-Info: AES-128-CBC,AE7ED92A1A2D5FB584B537DAB45A62E5

            J/o72rIJRjtdVzgu862AH79AXyZaSakgRh9SbzGQ00msDss6jKY6GxHhaZFutilO
            BX0bc3sOx+8XWlqnNpMiWy6ImorTMH6gr95SotrUQ3LuZ3rVW4u4FI+VQ/Jc7Dm9
            LkLj7N7hHGfsVw6SsywKtfYdnAu/ySg5hlngVDB+pTKNDon1Fzs8vYI4n0ou1OAM
            G/XjqMEbcQdqhhbdlLmmTElNRzIJE6Ojrx5swXmeVj31teH4oXKLiOVKNJQg37A+
            CN51VN8GNalqPBXeZ/DcguB18M/CMb8YTlk3V3XaOwgLLc1vXpDBz2rtkaluhcD+
            GzmquGZbcX9saP89Gq2HWzWzIMCuMFGHGSJlZvNp805PCVX0nF+iIqcwIArNRetA
            gAWxBIm6Y9f5Kp4kQE2UNFsaLKrQlKQHIPAwdQFBmIyQulYJ1qMJeCQvCTB8jPkk
            yrl2diiaK/TxexRaKgxeuML8jcSjfY8dYsaSda7gt8i/zCn+rSjc4CfOGSlCnSwl
            DDh3i9Yv0enZ+oHzRjYjwUFXxfQBg8MFSoth4YkPV2oMn+pKXyaIqe4AcFmlyYX7
            q3Vw+w86QA4gTX9d82nwW+svpiab1KqvnH78YJf4qYkcla54VbeuXApODE7INe9a
            +Hd9g3cQTnrJ7M5TiZViT/LPtRQHz6y9mckHHS0HsLZjHGrhrvlZHYPvdlE3AwJ8
            O6wGA7Ni51D1TjMPcTukb/H8VW+JhF4oUswVTjNkYE1C467GeT6q4H2oQ/Z9cIyR
            8IiI8jh4etL8QGyh3oFwQJ+j3i22zThyGylc58tyg6DbWF3modeOwd2p07tfuKH0
            M8ojQM9uo2SLZ5aOS65UzmawGXKQD7JjiuhQBupQrlb1w3tp1BNc0bSdcsckhGHK
            VZjCZNPTBbeoofsZf3xfUlxOG9HnWtJZ1ra8jlyQLTAD7VR+rjhZFSnRkY0POMg6
            iQFbJ4mM3i79Ut2MNz2kbYkL6uhHL0SDM1GABKWHxzhZ8+gb+ET6kraGJbxf5OTa
            JbVH5HmAkOaoLk8vnvhTG32gvEAF9ZDYQBiUq6g8jdbSVwh7XooIeJtU3zjiuzSv
            KRc1AbU/GDI6N5HZCQ0T7wr53dyEsUMdLRsVHrEN5wJrZFfV2hFi/zzgkTAcgYqf
            fHwjunoy8q2YyLKljR2WZpr5DJ+nFMsNedrhJSmr7N9wYMAWc5HO6ZmXOHVgkjlR
            ELUZDWHT7JImCpsqyBb1oAbPvX5Ehk6ONhwcAH0HW3XJ1B160wXkqwmS60fZVSgp
            Pyx1pzmAUP2yuc/gZqKo32jLbsh53ASScLgnhbjhwFgdK5dybUrK4traFOf9wabJ
            qBKqiJK8v6IAsZ6v0mCOSyS+uBat5+xOQlwuoKySNpbZRwHiQ3XdO+SsqrFMPDkc
            ZhJwE+Tw7tufE49IDaOG2CPLTj4MP+9jtl+GYe65xzvYS5CYMROUeA9lmzNxoDR5
            EqC8Dy/+T+mVZ+qD+w3kko9ShHEMf4NBWcrnv/bl3bia8KaiaXM3647H5P25il+E
            I9OzXdKPX937ozPzdb4jebRZZEHlviIm5bZLrIpqgtZIxTsQ8CY1gedL8nyX7EWX
            -----END RSA PRIVATE KEY-----
        """.trimIndent()

        private val PKCS8_RSA = """
            -----BEGIN PRIVATE KEY-----
            MIICdwIBADANBgkqhkiG9w0BAQEFAASCAmEwggJdAgEAAoGBAKbYgn2BYOQ9OVDf
            s01QHTNrmYKxDxuL77x/ISrDvCXxk0GHeq1Nycv6b41l9CNQLQFeJzs9wwtWbgDc
            gPYgBlAz5rF+r1oviG5Z6ubi5rq2923iBAqgvX7wkWZ0W8XDmlZb81ke2QimttV/
            sq1itTbtaYyFeu43TvVTOouzDTxzAgMBAAECgYAdDtw7K4tKdPdEhJFfx7IuQHEt
            99RfwdFxDNsj7Y8ZNKem5AeTo1af9e/zumv4soAEGvfOM4WCtfzWnZDATPeMPE1f
            Ns55nOG68ZcJfkahy4Q7ifkSEO9dEMXi6G269VXkWxC/+xM5HQ82jWcQuIADyTc1
            gPGtoHzervC2yYSu0QJBANhYyerybBF7j12Mhc0ol42K4tRjE7cIsj+/Hbv2AMqU
            qu3rvu2uvV7slmqb8NqkRYbnXW3DdxbreBD6rG2CTDUCQQDFbRR5o1ol3sMCmrAY
            BxLUmTKAIrDNDiez8KjXmpSAySoKSZBT0BT/NCm3b4GN1hV7nSv+evwrUpjCsGzK
            d2sHAkEAt73Q34W8WqzB+gWarb9H16KZOyBaYh00pgc+zaAE1AinkyGhKmQ52SC+
            LxT53feeRRDCvLJBtmmNs33ya5858QJAQwt4GJbQ1mt/7jJ5+q7sRiaAb+NYB0r3
            ULQ5on5sUBAApt20lcHkX4ZnZFQJuxEo2oHVnuZFHLFAD126lEdZoQJBAIz1tsK1
            8Z6yC9mWEFq+VZq+7yIK8mUBsZ1/lAyUVASQAGZmDzgXLc1QUIP5FcK5q1vg6j0/
            9Y8huNiILinSEsk=
            -----END PRIVATE KEY-----
        """.trimIndent()

        private val PKCS8_ECDSA = """
            -----BEGIN PRIVATE KEY-----
            MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgSO8998+yUgpmRqyc
            qJZdKVyBBPyJafnyrkXpW4QF52+hRANCAAT/wLRpL4zxm33zVOkTYAjzQ/oTzULv
            hQg0/XtsCcpp3FEF3gsnJJZXe9KENxr5pQ3QexZ0QcUSE/zWjx5zhCef
            -----END PRIVATE KEY-----
        """.trimIndent()

        private val PUTTY_ED25519 = """
            PuTTY-User-Key-File-2: ssh-ed25519
            Encryption: none
            Comment: root@sshj
            Public-Lines: 2
            AAAAC3NzaC1lZDI1NTE5AAAAIDAdJiRkkBM8yC8seTEoAn2PfwbLKrkcahZ0xxPo
            WICJ
            Private-Lines: 1
            AAAAIKaxyRDJxad8ZArpe1ClowY4NsCQxA50k0rpclKKkHt0
            Private-MAC: 388f807649f181243015cad9650633ec28b25208
        """.trimIndent()

        private val PUTTY_ED25519_ENCRYPTED = """
            PuTTY-User-Key-File-2: ssh-ed25519
            Encryption: aes256-cbc
            Comment: root@sshj
            Public-Lines: 2
            AAAAC3NzaC1lZDI1NTE5AAAAIDAdJiRkkBM8yC8seTEoAn2PfwbLKrkcahZ0xxPo
            WICJ
            Private-Lines: 1
            XFJyRzRt5NjuCVhDEyb50sI+gRn8FB65hh0U8uhGvP3VBl4haChinQasOTBYa4pj
            Private-MAC: 80f50e1a7075567980742644460edffeb67ca829
        """.trimIndent()
    }
}
