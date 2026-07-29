package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.common.service.credentialexchange.impl.CXF_MAX_COLLECTION_DEPTH
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfExportServiceImpl
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfImportServiceImpl
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The two round-trip facts [CxfRoundTripView] cannot model.
 *
 * Item-level fidelity lives in `CxfRoundTripViewTest`, which compares whole
 * projected views instead of hand-picked members. What is left here is the pair
 * the view has no place for: the *seam* an ssh key crosses (both legs are fakes,
 * so the only real assertion is which PEM was handed over), and the shape of the
 * folder tree, which is a property of the plan rather than of any one item.
 */
class CxfRoundTripTest {
    private val now = Instant.parse("2024-01-30T14:09:33Z")

    private val exportService = CxfExportServiceImpl(
        sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(byteArrayOf(1, 2, 3, 4, 5, 6)),
    )

    private val sshKeyImportService = FakeSshKeyImportService()

    private val importService = CxfImportServiceImpl(
        sshKeyImportService = sshKeyImportService,
    )

    private fun roundTrip(
        ciphers: List<DSecret>,
        folders: List<com.artemchep.keyguard.common.model.DFolder> = emptyList(),
    ): CxfImportPlan {
        val document = exportService.buildDocument(
            accounts = listOfNotNull(
                exportService.buildAccount(
                    profile = cxfProfile(),
                    ciphers = ciphers,
                    allowedTypes = CxfCredentialType.ALL,
                    folders = folders,
                ),
            ),
            exporterRpId = "com.artemchep.keyguard",
            exporterDisplayName = "Keyguard",
            timestamp = now,
        )
        val payload = exportService.encode(document)
        val result = importService.parse(
            payload = payload,
            now = now,
        )
        return assertIs<CxfImportResult.Success>(result).plan
    }

    @Test
    fun `ssh key item round-trips through the pkcs8 conversion seam`() {
        val original = cxfSecret(
            type = DSecret.Type.SshKey,
            sshKey = DSecret.SshKey(
                privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nfake\n" +
                    "-----END OPENSSH PRIVATE KEY-----\n",
                publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFake test@example.com",
                fingerprint = "SHA256:fakefingerprint",
            ),
        )
        val plan = roundTrip(listOf(original))
        val request = plan.items.single().request
        assertEquals(DSecret.Type.SshKey, request.type)
        // The export side produced the fake DER; the import side must have
        // handed exactly that DER (PEM-wrapped) to the conversion seam.
        val pem = sshKeyImportService.lastRequest?.content.orEmpty()
        assertTrue(pem.startsWith("-----BEGIN PRIVATE KEY-----\n"))
        assertTrue(pem.contains("AQIDBAUG"))
        assertEquals(fakeSshKeyPair().privateKey.ssh, request.sshKey.privateKey)
        assertEquals(fakeSshKeyPair().publicKey.ssh, request.sshKey.publicKey)
        assertEquals(fakeSshKeyPair().publicKey.fingerprint, request.sshKey.fingerprint)
    }

    @Test
    fun `folders round-trip as a nested tree`() {
        val folders = listOf(
            cxfFolder(
                id = "folder-1",
                name = "Work",
                hierarchyMode = FolderHierarchyMode.ParentId,
            ),
            cxfFolder(
                id = "folder-2",
                name = "Dev",
                parentId = "folder-1",
                hierarchyMode = FolderHierarchyMode.ParentId,
            ),
        )
        val original = cxfLoginSecret(
            folderId = "folder-2",
            login = DSecret.Login(
                username = "alice",
            ),
        )
        val plan = roundTrip(
            ciphers = listOf(original),
            folders = folders,
        )
        val actualTree = plan.folders.map { folder ->
            val parentTitle = plan.folders
                .firstOrNull { it.key == folder.parentKey }
                ?.title
            folder.title to parentTitle
        }
        assertEquals(
            listOf(
                "Work" to null,
                "Dev" to "Work",
            ),
            actualTree,
        )
        val item = plan.items.single()
        val dev = plan.folders.single { it.title == "Dev" }
        assertEquals(dev.key, item.folderKey)
    }

    @Test
    fun `a folder chain deeper than the collection cap round-trips without loss`() {
        // Both mappers read the same CXF_MAX_COLLECTION_DEPTH, so an over-deep
        // chain loses a parent edge on each side and nothing else.
        val total = CXF_MAX_COLLECTION_DEPTH + OVER_CAP
        val names = (0 until total)
            .scan("") { path, index ->
                if (path.isEmpty()) "a$index" else "$path/a$index"
            }
            .drop(1)
        val folders = names.mapIndexed { index, name ->
            cxfFolder(id = "f$index", name = name)
        }
        val plan = roundTrip(
            ciphers = listOf(
                cxfLoginSecret(
                    folderId = "f${total - 1}",
                    login = DSecret.Login(username = "alice"),
                ),
            ),
            folders = folders,
        )
        assertEquals(total, plan.folders.size)
        assertEquals(plan.folders.last().key, plan.items.single().folderKey)
    }

    @Test
    fun `duplicate path folder names round-trip as two folders`() {
        val plan = roundTrip(
            ciphers = listOf(
                cxfLoginSecret(id = "i1", folderId = "w1", login = DSecret.Login(username = "a")),
                cxfLoginSecret(id = "i2", folderId = "w2", login = DSecret.Login(username = "b")),
            ),
            folders = listOf(
                cxfFolder(id = "w1", name = "Work"),
                cxfFolder(id = "w2", name = "Work"),
            ),
        )
        // Two rows in, two folders out — every folder row is its own hierarchy
        // node, exactly as the vault's own browser lists them, so the round trip
        // neither merges the duplicates nor moves an item between them.
        val work = plan.folders.filter { it.title == "Work" }
        assertEquals(2, work.size)
        assertEquals(2, plan.folders.size)
        assertEquals(2, plan.items.size)
        // Each duplicate keeps exactly its own item.
        assertEquals(
            work.map { it.key }.toSet(),
            plan.items.map { it.folderKey }.toSet(),
        )
    }

    @Test
    fun `a path folder with no parent row round-trips with its path intact`() {
        // The deliberate no-synthesis decision: N folders in, N folders out, so
        // a re-export is idempotent. Nobody may "fix" this into a synthesized
        // "A" parent.
        val plan = roundTrip(
            ciphers = listOf(
                cxfLoginSecret(folderId = "ab", login = DSecret.Login(username = "alice")),
            ),
            folders = listOf(cxfFolder(id = "ab", name = "A/B")),
        )
        assertEquals("A/B", plan.folders.single().title)
    }
}

/** How far past the emission cap the round-trip depth case nests. */
private const val OVER_CAP = 44
