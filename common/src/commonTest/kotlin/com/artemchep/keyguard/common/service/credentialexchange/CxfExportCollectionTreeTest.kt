package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.common.service.credentialexchange.impl.CXF_MAX_COLLECTION_DEPTH
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfExportServiceImpl
import com.artemchep.keyguard.common.service.credentialexchange.impl.encodeIdToB64Url
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfAccount
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCollection
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The folder tree, and every way an item's collection membership is lost while
 * the item itself still exports. A lost membership raises no counter: the item
 * is in the document, it just lands at the account root on the other side.
 *
 * The hierarchy itself is lossless in every shape but one. Duplicate path names
 * merge into a single collection, as Keyguard's own folder browser shows them; a
 * path segment with no folder row of its own keeps its prefix in the title, so
 * concatenating titles down a chain reproduces the source path; and a subtree
 * past [CXF_MAX_COLLECTION_DEPTH] is re-rooted rather than dropped. Only a
 * re-rooted parent-id cycle loses a relationship.
 */
class CxfExportCollectionTreeTest {
    private val service = CxfExportServiceImpl(
        sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(byteArrayOf(1, 2, 3, 4, 5, 6)),
    )

    private fun account(
        ciphers: List<DSecret>,
        folders: List<DFolder>,
    ): CxfAccount? = service.buildAccountResult(
        profile = cxfProfile(),
        ciphers = ciphers,
        allowedTypes = CxfCredentialType.ALL,
        folders = folders,
    ).account

    private fun foldered(
        id: String,
        folderId: String?,
    ): DSecret = cxfLoginSecret(
        id = id,
        folderId = folderId,
        login = DSecret.Login(password = "s3cr3t"),
    )

    /** The (title, parentTitle) pairs of the whole tree, in emission order. */
    private fun CxfAccount.tree(): List<Pair<String, String?>> {
        val result = mutableListOf<Pair<String, String?>>()
        fun walk(node: CxfCollection, parentTitle: String?) {
            result += node.title to parentTitle
            node.subCollections.orEmpty().forEach { walk(it, node.title) }
        }
        collections.forEach { walk(it, null) }
        return result
    }

    private fun byId(id: String) = cxfFolder(
        id = id,
        name = id,
        hierarchyMode = FolderHierarchyMode.ParentId,
    )

    /** One malformed `parentId` shape and the roots it must render as. */
    private data class ReRootCase(
        val name: String,
        val folders: List<DFolder>,
        val itemFolderId: String,
        val expected: List<Pair<String, String?>>,
    )

    @Test
    fun `an item in a foreign or deleted folder still exports without membership`() {
        val folders = listOf(
            cxfFolder(id = "other", name = "Other", accountId = "acc-2"),
            cxfFolder(id = "gone", name = "Gone", deleted = true),
        )
        val result = service.buildAccountResult(
            profile = cxfProfile(),
            ciphers = listOf(foldered("i1", "other"), foldered("i2", "gone")),
            allowedTypes = CxfCredentialType.ALL,
            folders = folders,
        )
        val account = result.account
        // Both items are in the document...
        assertEquals(2, account?.items?.size)
        // ...but no collection exists to hold them, and nothing is counted.
        assertTrue(account?.collections?.isEmpty() == true)
        assertEquals(0, result.skips.totalCount)
    }

    @Test
    fun `an item whose folder has no row exports without membership`() {
        val account = account(listOf(foldered("i1", "missing")), emptyList())
        assertEquals(1, account?.items?.size)
        assertTrue(account?.collections?.isEmpty() == true)
    }

    @Test
    fun `a malformed parent id is re-rooted rather than dropped`() {
        // What the hierarchy index does with each malformed shape is pinned in
        // `FolderHierarchyIndexTest`; the CXF-layer delta is the single fact
        // that a re-rooted node renders as a `tree()` root instead of vanishing
        // from the document. All three shapes are asserted here together.
        listOf(
            ReRootCase(
                name = "a dangling parent",
                folders = listOf(byId("child").copy(name = "Child", parentId = "missing")),
                itemFolderId = "child",
                expected = listOf("Child" to null),
            ),
            ReRootCase(
                // Both folders survive — as siblings, with the nesting lost.
                name = "a parent id cycle",
                folders = listOf(
                    byId("f1").copy(parentId = "f2"),
                    byId("f2").copy(parentId = "f1"),
                ),
                itemFolderId = "f1",
                expected = listOf("f1" to null, "f2" to null),
            ),
            ReRootCase(
                name = "a self-parenting folder",
                folders = listOf(byId("f1").copy(parentId = "f1")),
                itemFolderId = "f1",
                expected = listOf("f1" to null),
            ),
        ).forEach { case ->
            val account = account(listOf(foldered("i1", case.itemFolderId)), case.folders)
            assertEquals(case.expected, account?.tree(), case.name)
        }
    }

    @Test
    fun `two path folders of the same name stay two collections`() {
        // Every folder row is its own hierarchy node -- exactly what the folder
        // browser shows (FolderBrowseTree lists them as separate entries) -- so
        // two rows named "Work" export as two same-titled collections, each
        // carrying only its own items. The child attaches to one of them: the
        // deterministic duplicate-path owner, the lexicographically smallest id.
        val folders = listOf(
            cxfFolder(id = "w1", name = "Work"),
            cxfFolder(id = "w2", name = "Work"),
            cxfFolder(id = "p1", name = "Work/Personal"),
        )
        val account = account(
            listOf(foldered("i1", "p1"), foldered("i2", "w1"), foldered("i3", "w2")),
            folders,
        )
        assertEquals(
            listOf("Work" to null, "Personal" to "Work", "Work" to null),
            account?.tree(),
        )
        val roots = account?.collections.orEmpty()
        assertEquals(2, roots.size)
        // Each row keeps its own id and only the items filed under that row.
        val byId = roots.associateBy { it.id }
        assertEquals(
            setOf(encodeIdToB64Url("w1"), encodeIdToB64Url("w2")),
            byId.keys,
        )
        assertEquals(
            listOf(encodeIdToB64Url("i2")),
            byId[encodeIdToB64Url("w1")]?.items?.map { it.item },
        )
        assertEquals(
            listOf(encodeIdToB64Url("i3")),
            byId[encodeIdToB64Url("w2")]?.items?.map { it.item },
        )
        // The child nests under the smallest-id duplicate, independent of the
        // input order of the two "Work" rows.
        assertEquals(
            1,
            byId[encodeIdToB64Url("w1")]?.subCollections?.size,
        )
        val reversed = account(
            listOf(foldered("i1", "p1"), foldered("i2", "w1"), foldered("i3", "w2")),
            folders.reversed(),
        )
        assertEquals(
            1,
            reversed?.collections
                ?.single { it.id == encodeIdToB64Url("w1") }
                ?.subCollections
                ?.size,
        )
    }

    @Test
    fun `a path with no row for its parent keeps the whole path as its title`() {
        // A folder named "A/B" with no "A" row exports as a *single* root whose
        // title is the full path. The nesting is not reconstructed, but nothing
        // is lost either — the prefix survives inside the title, so the other
        // side can still tell where the folder belonged.
        val account = account(
            listOf(foldered("i1", "ab")),
            listOf(cxfFolder(id = "ab", name = "A/B")),
        )
        assertEquals(listOf("A/B" to null), account?.tree())
    }

    @Test
    fun `a path nests only where a real folder row provides the parent`() {
        // With the "A" row present the same child *does* nest, and its title
        // narrows to the leaf segment. Contrast with the case above: whether the
        // prefix lives in the title or in the tree depends on the parent's
        // existence, and both shapes are lossless.
        val account = account(
            listOf(foldered("i1", "ab")),
            listOf(cxfFolder(id = "a", name = "A"), cxfFolder(id = "ab", name = "A/B")),
        )
        assertEquals(listOf("A" to null, "B" to "A"), account?.tree())
    }

    @Test
    fun `a path with a missing middle segment nests under its deepest existing ancestor`() {
        // "A/B" has no row, so "A/B/C" attaches to "A" and keeps the missing
        // level inside its own title. Concatenating titles down the chain
        // reproduces the source path exactly, so nothing is lost.
        val account = account(
            listOf(foldered("i1", "abc")),
            listOf(cxfFolder(id = "a", name = "A"), cxfFolder(id = "abc", name = "A/B/C")),
        )
        assertEquals(listOf("A" to null, "B/C" to "A"), account?.tree())
    }

    @Test
    fun `a path folder whose name-parent is a parent-id folder keeps its full path`() {
        // "A" exists but in ParentId mode, so the path folder "A/B" must not
        // adopt it — it stays a root carrying its whole path in the title,
        // rather than a root titled "B" with the "A/" prefix destroyed.
        val account = account(
            listOf(foldered("i1", "ab")),
            listOf(byId("a").copy(name = "A"), cxfFolder(id = "ab", name = "A/B")),
        )
        assertEquals(listOf("A" to null, "A/B" to null), account?.tree())
    }

    @Test
    fun `every folder is emitted exactly once even with a duplicate row id`() {
        // Two rows sharing an id cannot come through the DB, but the emission is
        // keyed by hierarchy node, so even this shape emits each folder once.
        val folders = listOf(
            cxfFolder(id = "dup", name = "Root"),
            cxfFolder(id = "child", name = "Root/Child"),
            cxfFolder(id = "dup", name = "Other"),
        )
        val account = account(listOf(foldered("i1", "child")), folders)
        assertEquals(
            listOf("Root" to null, "Child" to "Root", "Other" to null),
            account?.tree(),
        )
        // The item link is wired exactly once, under the one "Child".
        val links = account?.collections?.flatMap { it.allItemLinks() }
        assertEquals(listOf(encodeIdToB64Url("i1")), links)
    }

    @Test
    fun `a chain deeper than the collection cap is re-rooted, not dropped`() {
        val total = CXF_MAX_COLLECTION_DEPTH + OVER_CAP
        val names = (0 until total)
            .scan("") { path, index ->
                if (path.isEmpty()) "a$index" else "$path/a$index"
            }
            .drop(1)
        val folders = names.mapIndexed { index, name ->
            cxfFolder(id = "f$index", name = name)
        }
        val account = account(listOf(foldered("i1", "f${total - 1}")), folders)
        val collections = account?.collections.orEmpty()

        // Nothing is dropped, and the emitted nesting never exceeds the cap.
        assertEquals(total, collections.sumOf { it.countRecursively() })
        assertEquals(CXF_MAX_COLLECTION_DEPTH, collections.maxOf { it.maxDepth() })
        assertEquals(2, collections.size)
        // The re-rooted subtree keeps the whole path of its topmost folder in
        // its title, the same rule a path with no parent row follows.
        assertEquals(names[CXF_MAX_COLLECTION_DEPTH], collections[1].title)
        // The deepest folder still carries its item link.
        assertEquals(
            listOf(encodeIdToB64Url("i1")),
            collections.flatMap { it.allItemLinks() },
        )
        // That a document nested to the cap survives the recursive kotlinx
        // encode is asserted once, in the absurd-depth case below.
    }

    @Test
    fun `re-rooting at the cap is what preserves the nesting, not the sweep`() {
        // `buildCollections` keeps over-cap folders two ways: the cap hands them
        // to `reRoot`, and the main loop drains an `unswept` totality queue over
        // every node. The test above cannot tell the two apart, since its
        // folders arrive ascending by depth — an order in which the sweep alone
        // reproduces the re-rooting. Production order is not sorted, and feeding
        // the same chain deepest-first discriminates: only `reRoot` keeps the
        // over-cap subtree as ONE nested collection.
        val total = CXF_MAX_COLLECTION_DEPTH + OVER_CAP
        val names = (0 until total)
            .scan("") { path, index ->
                if (path.isEmpty()) "a$index" else "$path/a$index"
            }
            .drop(1)
        val folders = names
            .mapIndexed { index, name -> cxfFolder(id = "f$index", name = name) }
            .reversed()
        val collections = account(listOf(foldered("i1", "f${total - 1}")), folders)
            ?.collections
            .orEmpty()

        // Two roots: the original one, plus the single subtree re-rooted at the
        // cap. Nothing is dropped and the emitted nesting still honours the cap.
        assertEquals(2, collections.size)
        assertEquals(total, collections.sumOf { it.countRecursively() })
        assertEquals(CXF_MAX_COLLECTION_DEPTH, collections.maxOf { it.maxDepth() })
        // The re-rooted subtree is a chain, not OVER_CAP separate roots.
        assertEquals(OVER_CAP, collections[1].countRecursively())
        assertEquals(names[CXF_MAX_COLLECTION_DEPTH], collections[1].title)
        assertEquals(
            listOf(encodeIdToB64Url("i1")),
            collections.flatMap { it.allItemLinks() },
        )
    }

    @Test
    fun `an absurdly deep vault exports without a stack overflow`() {
        // The export mirror of the importer's hostile-nesting case. Emission
        // depth is capped regardless of the input depth, which is the property
        // under test; the folder count is preserved.
        val folders = (0 until ABSURD_DEPTH).map { index ->
            byId("f$index").copy(parentId = "f${index - 1}".takeIf { index > 0 })
        }
        val account = account(listOf(foldered("i1", "f0")), folders)
        val collections = account?.collections.orEmpty()
        assertEquals(ABSURD_DEPTH, collections.sumOf { it.countRecursively() })
        assertEquals(CXF_MAX_COLLECTION_DEPTH, collections.maxOf { it.maxDepth() })

        val document = service.buildDocument(
            accounts = listOfNotNull(account),
            exporterRpId = "com.artemchep.keyguard",
            exporterDisplayName = "Keyguard",
            timestamp = Instant.parse("2024-01-30T14:09:33Z"),
        )
        assertTrue(service.encode(document).isNotEmpty())
    }

    @Test
    fun `items in one folder are linked in cipher order`() {
        val folders = listOf(byId("f1"))
        val account = account(
            listOf(foldered("i1", "f1"), foldered("i2", "f1")),
            folders,
        )
        val collection = account?.collections?.single()
        assertEquals(
            account?.items?.map { it.id },
            collection?.items?.map { it.item },
        )
    }

    @Test
    fun `a leaf omits its subCollections but keeps an empty items array`() {
        val account = account(listOf(foldered("i1", folderId = null)), listOf(byId("f1")))
        val collection = account?.collections?.single()
        assertNull(collection?.subCollections, "an empty subCollections must be omitted")
        assertTrue(collection?.items?.isEmpty() == true, "items must survive as []")
    }

    @Test
    fun `a folder with both children and items carries both`() {
        val folders = listOf(byId("parent"), byId("child").copy(parentId = "parent"))
        val account = account(
            listOf(foldered("i1", "parent"), foldered("i2", "child")),
            folders,
        )
        val parent = account?.collections?.single()
        assertEquals(1, parent?.items?.size)
        assertEquals(1, parent?.subCollections?.size)
    }

    @Test
    fun `path and parent-id folders nest by their own modes side by side`() {
        val folders = listOf(
            cxfFolder(id = "p", name = "Paths"),
            cxfFolder(id = "pc", name = "Paths/Child"),
            byId("i"),
            byId("ic").copy(parentId = "i"),
        )
        val account = account(
            listOf(foldered("i1", "pc"), foldered("i2", "ic")),
            folders,
        )
        assertEquals(
            listOf("Paths" to null, "Child" to "Paths", "i" to null, "ic" to "i"),
            account?.tree(),
        )
    }
}

/** How far past the emission cap the re-rooting cases nest. */
private const val OVER_CAP = 44

/**
 * A folder chain far deeper than anything a real vault holds. The hierarchy
 * index build is quadratic, so this is kept modest — the property under test is
 * that emission depth stays capped no matter how deep the input is.
 */
private const val ABSURD_DEPTH = 2_000

/** Every collection in this subtree, including itself. */
private fun CxfCollection.countRecursively(): Int = 1 +
    subCollections.orEmpty().sumOf { it.countRecursively() }

/** The deepest nesting level in this subtree, counting itself as level 1. */
private fun CxfCollection.maxDepth(): Int = 1 +
    (subCollections.orEmpty().maxOfOrNull { it.maxDepth() } ?: 0)

/** Every linked item id in this subtree, in pre-order. */
private fun CxfCollection.allItemLinks(): List<String> = items.map { it.item } +
    subCollections.orEmpty().flatMap { it.allItemLinks() }
