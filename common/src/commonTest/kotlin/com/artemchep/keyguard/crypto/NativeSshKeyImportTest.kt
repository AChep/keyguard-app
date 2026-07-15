package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.service.crypto.SshKeyImportError
import com.artemchep.keyguard.common.service.crypto.SshKeyImportRequest
import com.artemchep.keyguard.common.service.crypto.SshKeyImportResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NativeSshKeyImportTest {
    @Test
    fun importsOpenSshEd25519() {
        val result =
            NativeSshKeyImportService.import(
                SshKeyImportRequest(content = OPENSSH_ED25519),
            )

        val success = assertIs<SshKeyImportResult.Success>(result)
        assertEquals(KeyPair.Type.ED25519, success.keyPair.type)
        assertEquals(EXPECTED_PUBLIC_KEY, success.keyPair.publicKey.ssh)
    }

    @Test
    fun importsEncryptedOpenSshAeadVariants() {
        assertEncryptedImport(OPENSSH_ED25519_AES256_GCM)
        assertEncryptedImport(OPENSSH_ED25519_CHACHA20_POLY1305)
    }

    private fun assertEncryptedImport(content: String) {
        val result =
            NativeSshKeyImportService.import(
                SshKeyImportRequest(
                    content = content,
                    passphrase = OPENSSH_AEAD_PASSPHRASE,
                ),
            )
        val success = assertIs<SshKeyImportResult.Success>(result)
        assertEquals(KeyPair.Type.ED25519, success.keyPair.type)
        assertEquals(EXPECTED_PUBLIC_KEY, success.keyPair.publicKey.ssh)

        assertEquals(
            SshKeyImportResult.Error(SshKeyImportError.InvalidPassphrase),
            NativeSshKeyImportService.import(
                SshKeyImportRequest(
                    content = content,
                    passphrase = "wrong-passphrase",
                ),
            ),
        )
    }
}

private const val OPENSSH_ED25519: String = """-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
QyNTUxOQAAACCzPq7zfqLffKoBDe/eo04kH2XxtSmk9D7RQyf1xUqrYgAAAJgAIAxdACAM
XQAAAAtzc2gtZWQyNTUxOQAAACCzPq7zfqLffKoBDe/eo04kH2XxtSmk9D7RQyf1xUqrYg
AAAEC2BsIi0QwW2uFscKTUUXNHLsYX4FxlaSDSblbAj7WR7bM+rvN+ot98qgEN796jTiQf
ZfG1KaT0PtFDJ/XFSqtiAAAAEHVzZXJAZXhhbXBsZS5jb20BAgMEBQ==
-----END OPENSSH PRIVATE KEY-----"""

private const val EXPECTED_PUBLIC_KEY: String =
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILM+rvN+ot98qgEN796jTiQfZfG1KaT0PtFDJ/XFSqti"
