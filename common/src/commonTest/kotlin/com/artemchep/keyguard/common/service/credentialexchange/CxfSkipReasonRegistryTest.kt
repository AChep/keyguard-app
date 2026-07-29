package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfAccountMapper
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfExportServiceImpl
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfImportServiceImpl
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The anti-dead-reason guard: a skip reason may not exist before the code that
 * raises it does.
 *
 * Each side keeps a map of one representative input per reason. The test drives
 * the input and asserts the reason fires, **and** asserts the map's keys are the
 * complete enum — so adding a reason without a producer, or without a fixture,
 * fails here rather than shipping as a row the user can never see.
 */
class CxfSkipReasonRegistryTest {
    private val now = Instant.parse("2024-01-30T14:09:33Z")

    private val importService = CxfImportServiceImpl(FakeSshKeyImportService())

    private val exportService = CxfExportServiceImpl(
        sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(byteArrayOf(1, 2, 3, 4, 5, 6)),
    )

    /**
     * One CXF document per import reason, chosen to be the smallest input that
     * raises it.
     */
    private val importFixtures: Map<CxfImportSkipReason, String> = mapOf(
        CxfImportSkipReason.Passkey to
            documentWithItems(
                registryItem(cxfPasskeyCredentialJson(credentialId = "###"), REGISTRY_BASIC_AUTH),
            ),
        CxfImportSkipReason.Otp to
            documentWithItems(registryItem(cxfTotpCredentialJson(digits = 10), REGISTRY_BASIC_AUTH)),
        CxfImportSkipReason.SshKey to
            documentWithItems(registryItem(cxfSshKeyCredentialJson(privateKey = "not base64url!"))),
        CxfImportSkipReason.UnknownCredential to
            documentWithItems(registryItem(REGISTRY_WIFI, REGISTRY_BASIC_AUTH)),
        CxfImportSkipReason.DuplicateCredential to
            documentWithItems(registryItem(REGISTRY_BASIC_AUTH, REGISTRY_BASIC_AUTH)),
        CxfImportSkipReason.Item to documentWithItems(""""not-an-item""""),
        CxfImportSkipReason.Collection to documentWithAccount(
            collectionsJson = """[{"id": "YmFk", "title": 42, "items": []}]""",
            itemsJson = "[]",
        ),
        CxfImportSkipReason.Account to documentWithAccount(
            collectionsJson = "[]",
            itemsJson = "null",
        ),
    )

    /**
     * A service that always raises while mapping, so the one export reason the
     * mapper can never produce still has a producer to point at.
     */
    private val raisingExportService = CxfExportServiceImpl(
        mapper = object : CxfAccountMapper {
            override fun buildAccountResult(
                profile: DProfile,
                ciphers: List<DSecret>,
                allowedTypes: Set<CxfCredentialType>,
                folders: List<DFolder>,
            ): CxfAccountResult = error("mapping is broken")
        },
    )

    /**
     * One export run per export reason, each the smallest input that raises it.
     *
     * Most entries drive the ordinary service over one vault item, because the
     * *mapper* is what raises them. [CxfExportSkipReason.Account] is the
     * exception and is deliberately shaped differently: it is raised at the
     * `CxfExportService` boundary and the mapper never can raise it, so its
     * fixture swaps the mapper for one that fails rather than swapping the vault
     * item. Any future reason raised above the mapper belongs here in the same
     * shape.
     */
    private val exportFixtures: Map<CxfExportSkipReason, () -> CxfAccountResult> = mapOf(
        CxfExportSkipReason.Passkey to {
            exportOf(
                cxfLoginSecret(
                    login = DSecret.Login(
                        password = "s3cr3t",
                        fido2Credentials = listOf(cxfFido2Credential(userHandle = "###")),
                    ),
                ),
            )
        },
        CxfExportSkipReason.Otp to {
            exportOf(cxfLoginSecret(login = DSecret.Login(password = "s3cr3t", totp = cxfHotpAuth())))
        },
        CxfExportSkipReason.SshKey to {
            exportOf(
                cxfSecret(
                    type = DSecret.Type.SshKey,
                    sshKey = DSecret.SshKey(privateKey = "pem", publicKey = null),
                ),
            )
        },
        CxfExportSkipReason.GpgKey to {
            exportOf(cxfSecret(type = DSecret.Type.GpgKey).copy(gpgKey = cxfGpgKey()))
        },
        CxfExportSkipReason.Attachment to {
            exportOf(cxfSecret().copy(attachments = listOf(cxfAttachment())))
        },
        CxfExportSkipReason.PasswordHistory to {
            exportOf(cxfSecret().copy(passwordHistory = listOf(cxfPasswordHistory())))
        },
        CxfExportSkipReason.Archived to {
            exportOf(
                cxfLoginSecret(login = DSecret.Login(password = "s3cr3t"))
                    .copy(archivedDate = Instant.parse("2024-02-01T00:00:00Z")),
            )
        },
        CxfExportSkipReason.Item to { exportOf(cxfSecret(type = DSecret.Type.GpgKey)) },
        CxfExportSkipReason.Account to {
            raisingExportService.buildAccountResult(
                profile = cxfProfile(),
                ciphers = listOf(cxfLoginSecret(login = DSecret.Login(password = "s3cr3t"))),
                allowedTypes = CxfCredentialType.ALL,
            )
        },
    )

    private fun exportOf(secret: DSecret): CxfAccountResult = exportService.buildAccountResult(
        profile = cxfProfile(),
        ciphers = listOf(secret),
        allowedTypes = CxfCredentialType.ALL,
    )

    @Test
    fun `every import skip reason is produced by a fixture`() {
        assertEquals(
            CxfImportSkipReason.entries.toSet(),
            importFixtures.keys,
            "every import reason needs a fixture that proves something raises it",
        )
        importFixtures.forEach { (reason, payload) ->
            val plan = importService.parseSuccessPlan(payload = payload, now = now)
            assertTrue(
                plan.skips[reason] > 0,
                "$reason was not raised by its fixture; tally was ${plan.skips}",
            )
        }
    }

    @Test
    fun `every export skip reason is produced by a fixture`() {
        assertEquals(
            CxfExportSkipReason.entries.toSet(),
            exportFixtures.keys,
            "every export reason needs a fixture that proves something raises it",
        )
        exportFixtures.forEach { (reason, run) ->
            val result = run()
            assertTrue(
                result.skips[reason] > 0,
                "$reason was not raised by its fixture; tally was ${result.skips}",
            )
        }
    }

    @Test
    fun `the two reason sets differ only where the direction forces it`() {
        // Import is the wider vocabulary, and export stays a subset of it for
        // every reason that describes a *credential* that could not be
        // represented: export authors its own credential list, so it can never
        // see an unknown or duplicated credential, and it builds its collection
        // tree from vault folders it already holds, so nothing there can fail to
        // be read. Such an export-only reason would mean a loss the import
        // screen has no sentence for.
        //
        // `Archived` is one deliberate exception, and it is exempt because it is
        // not a representability failure at all: it is Keyguard choosing to
        // withhold an item it could encode perfectly well. The asymmetry is
        // forced by the format, which has no archive member — so an incoming
        // document can never say "this item was archived", and the import screen
        // has nothing to report.
        //
        // `GpgKey`, `Attachment` and `PasswordHistory` are exempt on the same
        // grounds one step down: each names a *member of a vault item* rather
        // than a source credential, so only the outgoing direction can meet one.
        // CXF spells no OpenPGP key and no password history at all, so an
        // incoming document cannot carry either; a `file` credential is the one
        // incoming shape that corresponds to an attachment, and the import side
        // already counts it as `UnknownCredential` along with every other kind it
        // does not model, so it is owed no row of its own.
        //
        // Anything added here needs the same argument written down, not just a
        // new entry.
        val exportOnly = setOf(
            CxfExportSkipReason.Archived.name,
            CxfExportSkipReason.GpgKey.name,
            CxfExportSkipReason.Attachment.name,
            CxfExportSkipReason.PasswordHistory.name,
        )
        assertTrue(
            CxfExportSkipReason.entries.all {
                it.name in exportOnly ||
                    it.name in CxfImportSkipReason.entries.map(Enum<*>::name)
            },
        )
    }
}

private const val REGISTRY_BASIC_AUTH =
    """{"type": "basic-auth", "username": {"fieldType": "string", "value": "alice"}}"""

private const val REGISTRY_WIFI =
    """{"type": "wifi", "ssid": {"fieldType": "string", "value": "g"}}"""

private fun registryItem(
    vararg credentialsJson: String,
): String = """
    {
      "id": "aXRlbTAx",
      "title": "Item",
      "credentials": [${credentialsJson.joinToString(separator = ",")}]
    }
""".trimIndent()
