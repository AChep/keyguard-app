package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.crypto.NativeSshKeyImportService
import com.artemchep.keyguard.test.FakeSshKeyImportService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Keeper's own SSH rules; [KeeperSshImportContractTest] covers what every
 * importer guarantees.
 */
class KeeperSshImportTest {
    @Test
    fun `every typed key field name is read`() = runTest {
        val privateKey = sshCorpusText("openssh/id_ed25519")
        for (fieldName in listOf("\$keyPair", "\$keyPair:Deploy: primary", "\$privateKey:Key")) {
            val request = parse(listOf(fieldName to privateKey)).items.single().request
            assertEquals(DSecret.Type.SshKey, request.type, fieldName)
            assertEquals(sshCorpusGolden("ssh-key-ed25519")[2], request.sshKey.publicKey, fieldName)
            assertTrue(request.fields.none { it.value == privateKey }, fieldName)
        }
    }

    @Test
    fun `key fields on ordinary logins are hidden without changing the item type`() = runTest {
        for (recordType in listOf(null, "login", "custom")) {
            val service = FakeSshKeyImportService(error = IllegalStateException("Must not convert"))
            val plan = parse(
                listOf("\$keyPair:Deploy: primary" to "key one", "\$privateKey" to "key two"),
                recordType = recordType,
                service = service,
            )
            val request = plan.items.single().request
            assertEquals(DSecret.Type.Login, request.type)
            assertEquals("deploy", request.login.username)
            assertEquals(PASSPHRASE, request.login.password)
            assertEquals(0, service.callCount)
            assertTrue(plan.skips.isEmpty)
            assertEquals(
                listOf(
                    DSecret.Field("Deploy: primary", "key one", type = DSecret.Field.Type.Hidden),
                    DSecret.Field("privateKey", "key two", type = DSecret.Field.Type.Hidden),
                ),
                request.fields.filter { it.value in listOf("key one", "key two") },
            )
        }
    }

    @Test
    fun `only the converted key field is consumed`() = runTest {
        val privateKey = sshCorpusText("openssh/id_ed25519")
        val plan = parse(
            listOf(
                "\$keyPair:Empty" to "",
                "\$keyPair:Primary" to privateKey,
                "\$keyPair:Backup" to privateKey,
            ),
        )
        val request = plan.items.single().request
        assertEquals(DSecret.Type.SshKey, request.type)
        assertEquals(
            listOf(DSecret.Field("Backup", privateKey, type = DSecret.Field.Type.Hidden)),
            request.fields.filter { it.value == privateKey },
        )
        assertTrue(plan.skips.isEmpty)
    }

    private suspend fun parse(
        keyFields: List<Pair<String, String>>,
        recordType: String? = "sshKeys",
        service: SshKeyImportService = NativeSshKeyImportService,
    ): ImportPlan {
        val custom = listOf("\$record_uid" to "source-id") +
            listOfNotNull(recordType?.let { "\$type" to it }) + keyFields
        val values = listOf(
            "Infrastructure", "Deploy key", "deploy", PASSPHRASE, "ssh://server.example.org", "", "",
        ) + custom.flatMap { (name, value) -> listOf(name, value) }
        val csv = values.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" } + "\r\n"
        val result = importTestService(sshKeyImportService = service).parse(
            format = ImportFormatId.Keeper,
            input = BytesImportInput(csv),
            now = IMPORT_TEST_NOW,
        )
        return assertIs<ImportResult.Success>(result).plan.also { it.assertVaultStorable() }
    }

    private companion object {
        const val PASSPHRASE = "  secret, \"quoted\" passphrase  "
    }
}
