package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The Keyguard file of the [ImportCorpus], written by the exporter of the
 * Keyguard version its provenance names.
 */
class KeyguardImportCorpusTest {
    @Test
    fun `export restores its own item types and attachment`() = runTest {
        val plan = ImportCorpus.successPlan("keyguard-zip")
        assertEquals("Keyguard", plan.source.displayName)
        assertEquals(listOf("Work", "Email"), plan.folders.map { it.title })
        val byTitle = plan.items.associateBy { it.request.title }

        with(byTitle.getValue("Mail")) {
            assertEquals(plan.folders[1].key, folderKey)
            assertEquals(
                "otpauth://totp/Example:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example",
                request.login.totp,
            )
            assertEquals(listOf("mail.example.com"), request.fido2Credentials.map { it.rpId })
            // The link to an item of the source account is not carried.
            assertEquals(
                listOf("PIN", "Recovery", "Remember me", "Login"),
                request.fields.map { it.name },
            )
        }
        assertTrue(byTitle.getValue("Old forum").archived)
        with(byTitle.getValue("Signing key").request) {
            assertEquals(DSecret.Type.GpgKey, type)
            assertEquals("0123456789ABCDEF0123456789ABCDEF01234567", gpgKey.fingerprint)
        }
        with(byTitle.getValue("Deploy").request) {
            assertEquals(DSecret.Type.Environment, type)
            val variable = environment!!.definition.variables.single()
            assertEquals("API_TOKEN", variable.name)
            assertEquals(EnvironmentValueSource.Literal("synthetic-token"), variable.source)
        }
        with(byTitle.getValue("Server key").request) {
            // Keyguard's own SSH keys come back as they were written.
            assertEquals(DSecret.Type.SshKey, type)
            assertEquals(sshCorpusText("openssh/id_ed25519"), sshKey.privateKey)
            assertEquals(sshCorpusText("openssh/id_ed25519.pub").trim(), sshKey.publicKey)
        }

        val attachment = byTitle.getValue("Manual").attachments.single()
        assertEquals("manual.txt", attachment.name)
        val files = ImportCorpus.extractAttachments(
            id = "keyguard-zip",
            refs = setOf(attachment.ref),
        )
        assertEquals("Synthetic attachment of the import corpus.\n", files.getValue(attachment.ref))
    }
}
