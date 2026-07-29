package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfExportServiceImpl
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfImportServiceImpl
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * The DER the fake exporter hands out; base64url `AQIDBAUG`.
 */
internal val FAKE_PKCS8_DER = byteArrayOf(1, 2, 3, 4, 5, 6)

/**
 * The plan-level CXF round trip: a vault item out through the exporter, over
 * the JSON wire, and back in through the importer. It stops at the plan — no
 * database, no commit — so ownership, the untitled-title fallback and folder
 * creation are all out of scope.
 *
 * The ssh-key leg crosses a crypto fake in both directions — the exporter's DER
 * and the importer's key pair are both canned — so **an ssh key round-trips as a
 * seam, not as a value**. What the mapper actually built is observable through
 * [pkcs8Exporter]'s recorded PEMs.
 */
class CxfRoundTripHarness(
    val now: Instant = Instant.parse("2024-01-30T14:09:33Z"),
    val pkcs8Exporter: FakeSshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(FAKE_PKCS8_DER),
    val sshKeyImportService: FakeSshKeyImportService = FakeSshKeyImportService(),
) {
    private val exportService = CxfExportServiceImpl(
        sshKeyPkcs8Exporter = pkcs8Exporter,
    )

    private val importService = CxfImportServiceImpl(
        sshKeyImportService = sshKeyImportService,
    )

    /**
     * The key pair the import seam hands back, which is what an ssh key
     * necessarily looks like after a round trip through the fakes.
     */
    private val cannedSshKey: CreateRequest.SshKey = fakeSshKeyPair().let { pair ->
        CreateRequest.SshKey(
            privateKey = pair.privateKey.ssh,
            publicKey = pair.publicKey.ssh,
            fingerprint = pair.publicKey.fingerprint,
        )
    }

    fun roundTrip(
        secrets: List<DSecret>,
        folders: List<DFolder> = emptyList(),
        allowedTypes: Set<CxfCredentialType> = CxfCredentialType.ALL,
    ): CxfRoundTripResult {
        val exported = exportService.buildAccountResult(
            profile = cxfProfile(),
            ciphers = secrets,
            allowedTypes = allowedTypes,
            folders = folders,
        )
        val document = exportService.buildDocument(
            accounts = listOfNotNull(exported.account),
            exporterRpId = "com.artemchep.keyguard",
            exporterDisplayName = "Keyguard",
            timestamp = now,
        )
        val payload = exportService.encode(document)
        val result = importService.parse(payload = payload, now = now)
        return CxfRoundTripResult(
            plan = assertIs<CxfImportResult.Success>(result).plan,
            exportSkips = exported.skips,
        )
    }

    /**
     * The two views of a single item, ready for one `assertEquals`.
     */
    fun views(
        secret: DSecret,
        folders: List<DFolder> = emptyList(),
    ): CxfRoundTripViews {
        val result = roundTrip(secrets = listOf(secret), folders = folders)
        val folderTitle = result.plan.items
            .firstOrNull()
            ?.folderKey
            ?.let { key -> result.plan.folders.firstOrNull { it.key == key }?.title }
        val expected = secret.toCxfRoundTripView(now = now, folderTitle = folderTitle)
        return CxfRoundTripViews(
            expected = expected.copy(
                // The ssh key crosses a fake in both directions, so the only
                // honest expectation is the seam's own output.
                sshKey = expected.sshKey?.let { cannedSshKey },
            ),
            actual = result.plan.items.map { it.request }.toCxfRoundTripView(folderTitle),
            importSkips = result.plan.skips,
            exportSkips = result.exportSkips,
        )
    }
}

data class CxfRoundTripResult(
    val plan: CxfImportPlan,
    val exportSkips: CxfExportSkips,
)

data class CxfRoundTripViews(
    val expected: CxfRoundTripView,
    val actual: CxfRoundTripView,
    val importSkips: CxfImportSkips,
    val exportSkips: CxfExportSkips,
)

/**
 * The one-liner the great majority of cases use.
 *
 * Skips are *declared*, never derived — deriving them would re-implement the
 * mappers' accounting. The empty defaults make "nothing was lost" the quiet
 * case and every loss an explicit line in the test.
 */
fun CxfRoundTripHarness.assertRoundTrips(
    secret: DSecret,
    folders: List<DFolder> = emptyList(),
    expectedImportSkips: CxfImportSkips = cxfImportSkips(),
    expectedExportSkips: CxfExportSkips = cxfExportSkips(),
) {
    val views = views(secret = secret, folders = folders)
    assertEquals(views.expected, views.actual)
    assertEquals(expectedExportSkips, views.exportSkips, "export skips")
    assertEquals(expectedImportSkips, views.importSkips, "import skips")
}
