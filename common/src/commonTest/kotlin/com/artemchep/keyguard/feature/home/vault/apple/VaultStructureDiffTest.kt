package com.artemchep.keyguard.feature.home.vault.apple

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultStructureDiffTest {
    private fun entry(
        id: String,
        kind: Int = AppleVaultEntry.KIND_ITEM,
    ) = AppleVaultEntry(
        id = id,
        kind = kind,
    )

    private fun entries(vararg ids: String) = ids.map { entry(it) }

    private fun apply(
        old: List<AppleVaultEntry>,
        ops: List<AppleVaultStructureOp>,
        new: List<AppleVaultEntry>,
    ): List<AppleVaultEntry> {
        val newById = new.associateBy { it.id }
        val working = old.toMutableList()
        // Phase 1: remove every REMOVE index and MOVE fromIndex â all
        // positions in the OLD list â highest index first.
        val extractions = ops.mapNotNull { op ->
            when (op.kind) {
                AppleVaultStructureOp.KIND_REMOVE -> op.index to op.id
                AppleVaultStructureOp.KIND_MOVE -> op.fromIndex to op.id
                AppleVaultStructureOp.KIND_INSERT -> null
                else -> error("Unknown op kind ${op.kind}")
            }
        }
        for ((index, id) in extractions.sortedByDescending { it.first }) {
            assertEquals(
                id, working[index].id,
                "extraction id must match the entry at its old index",
            )
            working.removeAt(index)
        }
        // Phase 2: iterate the ops in order, inserting every INSERT / MOVE
        // at its target index.
        for (op in ops) {
            if (op.kind == AppleVaultStructureOp.KIND_INSERT || op.kind == AppleVaultStructureOp.KIND_MOVE) {
                working.add(op.index, newById.getValue(op.id))
            }
        }
        return working
    }

    /** Asserts the pre-sort contract of an ops list. */
    private fun assertOpsOrdering(ops: List<AppleVaultStructureOp>) {
        val firstPlacement = ops.indexOfFirst { it.kind != AppleVaultStructureOp.KIND_REMOVE }
        val removals = if (firstPlacement == -1) ops else ops.subList(0, firstPlacement)
        val placements = if (firstPlacement == -1) emptyList() else ops.subList(firstPlacement, ops.size)
        assertTrue(
            removals.all { it.kind == AppleVaultStructureOp.KIND_REMOVE },
            "all REMOVE ops must precede all INSERT / MOVE ops",
        )
        assertTrue(
            placements.none { it.kind == AppleVaultStructureOp.KIND_REMOVE },
            "no REMOVE ops after the first placement op",
        )
        for (i in 1 until removals.size) {
            assertTrue(
                removals[i - 1].index > removals[i].index,
                "REMOVE ops must be sorted by index descending",
            )
        }
        for (i in 1 until placements.size) {
            assertTrue(
                placements[i - 1].index < placements[i].index,
                "INSERT / MOVE ops must be sorted by target index ascending",
            )
        }
        for (op in placements) {
            if (op.kind == AppleVaultStructureOp.KIND_MOVE) {
                assertTrue(op.fromIndex >= 0, "MOVE ops must carry their old index")
            } else {
                assertEquals(-1, op.fromIndex, "INSERT ops must carry the -1 sentinel")
            }
        }
        for (op in removals) {
            assertEquals(-1, op.fromIndex, "REMOVE ops must carry the -1 sentinel")
        }
    }

    @Test
    fun `identical lists produce no ops and no full fallback`() {
        val list = entries("a", "b", "c", "d")
        val result = diffStructure(
            old = list,
            new = list.toList(),
            opsEnabled = true,
        )
        assertFalse(result.isFull)
        assertTrue(result.ops.isEmpty())
    }

    @Test
    fun `empty old and empty new is a no-op`() {
        val result = diffStructure(
            old = emptyList(),
            new = emptyList(),
            opsEnabled = true,
        )
        assertFalse(result.isFull)
        assertTrue(result.ops.isEmpty())
    }

    @Test
    fun `ops disabled always falls back to full`() {
        val result = diffStructure(
            old = entries("a", "b"),
            new = entries("a", "b", "c"),
            opsEnabled = false,
        )
        assertTrue(result.isFull)
        assertTrue(result.ops.isEmpty())
    }

    @Test
    fun `default flag keeps ops disabled for the dark launch`() {
        assertFalse(STRUCTURE_OPS_DEFAULT)
        val result = diffStructure(
            old = entries("a", "b"),
            new = entries("b", "a"),
        )
        assertTrue(result.isFull)
    }

    @Test
    fun `adjacent swaps produce a single move that applies correctly`() {
        val ids = listOf("a", "b", "c", "d", "e", "f")
        for (i in 0 until ids.size - 1) {
            val old = entries(*ids.toTypedArray())
            val swapped = ids.toMutableList()
                .apply {
                    val tmp = this[i]
                    this[i] = this[i + 1]
                    this[i + 1] = tmp
                }
            val new = entries(*swapped.toTypedArray())
            val result = diffStructure(
                old = old,
                new = new,
                opsEnabled = true,
                churnThreshold = 1000.0,
            )
            assertFalse(result.isFull)
            assertEquals(
                1, result.ops.size,
                "an adjacent swap at $i must cost exactly one MOVE",
            )
            assertEquals(AppleVaultStructureOp.KIND_MOVE, result.ops.single().kind)
            assertEquals(new, apply(old, result.ops, new))
        }
    }

    @Test
    fun `pure removals apply in descending index order`() {
        val old = entries("a", "b", "c", "d", "e")
        val new = entries("b", "d")
        val result = diffStructure(
            old = old,
            new = new,
            opsEnabled = true,
            churnThreshold = 1000.0,
        )
        assertFalse(result.isFull)
        assertTrue(result.ops.all { it.kind == AppleVaultStructureOp.KIND_REMOVE })
        assertOpsOrdering(result.ops)
        assertEquals(new, apply(old, result.ops, new))
    }

    @Test
    fun `pure inserts apply in ascending index order`() {
        val old = entries("b", "d")
        val new = entries("a", "b", "c", "d", "e")
        val result = diffStructure(
            old = old,
            new = new,
            opsEnabled = true,
            churnThreshold = 1000.0,
        )
        assertFalse(result.isFull)
        assertTrue(result.ops.all { it.kind == AppleVaultStructureOp.KIND_INSERT })
        assertOpsOrdering(result.ops)
        assertEquals(new, apply(old, result.ops, new))
    }

    @Test
    fun `kind change on a surviving id falls back to full`() {
        val result = diffStructure(
            old = listOf(entry("a", kind = AppleVaultEntry.KIND_ITEM)),
            new = listOf(entry("a", kind = AppleVaultEntry.KIND_SECTION)),
            opsEnabled = true,
            churnThreshold = 1000.0,
        )
        assertTrue(result.isFull)
    }

    @Test
    fun `duplicate ids fall back to full`() {
        assertTrue(
            diffStructure(
                old = entries("a", "a"),
                new = entries("a"),
                opsEnabled = true,
                churnThreshold = 1000.0,
            ).isFull,
        )
        assertTrue(
            diffStructure(
                old = entries("a"),
                new = entries("a", "a"),
                opsEnabled = true,
                churnThreshold = 1000.0,
            ).isFull,
        )
    }

    @Test
    fun `churn above the threshold falls back to full`() {
        val old = (0 until 20).map { entry("id$it") }
        val new = old.reversed()
        val result = diffStructure(
            old = old,
            new = new,
            opsEnabled = true,
        )
        assertTrue(
            result.isFull,
            "reversing 20 entries must blow the default op budget",
        )
    }

    @Test
    fun `churn just below the threshold stays incremental`() {
        val old = (0 until 20).map { entry("id$it") }
        // A single removal: 1 op <= 19 * 0.3.
        val new = old.drop(1)
        val result = diffStructure(
            old = old,
            new = new,
            opsEnabled = true,
        )
        assertFalse(result.isFull)
        assertEquals(1, result.ops.size)
        assertEquals(new, apply(old, result.ops, new))
    }

    @Test
    fun `populating an empty list falls back to full via the op budget`() {
        val new = (0 until 10).map { entry("id$it") }
        val result = diffStructure(
            old = emptyList(),
            new = new,
            opsEnabled = true,
        )
        assertTrue(result.isFull)
    }

    @Test
    fun `emptying the list always falls back to full`() {
        val old = (0 until 10).map { entry("id$it") }
        val result = diffStructure(
            old = old,
            new = emptyList(),
            opsEnabled = true,
            churnThreshold = 1000.0,
        )
        // The op budget is relative to the new size, so any op against an
        // empty target blows it â an emptied list transfers whole.
        assertTrue(result.isFull)
    }

    @Test
    fun `randomized permutations inserts and removals reproduce the target`() {
        val rng = Random(20260702)
        repeat(400) { iteration ->
            val oldSize = rng.nextInt(0, 32)
            val old = (0 until oldSize).map { entry("id$it") }

            // Remove ~25% of the entries.
            val survivors = old.filter { rng.nextInt(100) >= 25 }
                .toMutableList()
            // A few single-element moves.
            repeat(rng.nextInt(0, 4)) {
                if (survivors.size >= 2) {
                    val from = rng.nextInt(survivors.size)
                    val moved = survivors.removeAt(from)
                    survivors.add(rng.nextInt(survivors.size + 1), moved)
                }
            }
            // Occasionally churn the whole list.
            if (rng.nextInt(100) < 20) {
                survivors.shuffle(rng)
            }
            // Sprinkle in some fresh entries.
            val new = survivors.toMutableList()
            repeat(rng.nextInt(0, 5)) { insertion ->
                new.add(
                    rng.nextInt(new.size + 1),
                    entry("new-$iteration-$insertion"),
                )
            }

            val result = diffStructure(
                old = old,
                new = new,
                opsEnabled = true,
                churnThreshold = 1000.0,
            )
            if (new.isEmpty() && old.isNotEmpty()) {
                // The op budget is relative to the new size, so emptying a
                // list always transfers whole â by design.
                assertTrue(result.isFull)
                return@repeat
            }
            assertFalse(
                result.isFull,
                "iteration $iteration must not fall back with an unlimited budget",
            )
            assertOpsOrdering(result.ops)
            assertEquals(
                new.map { it.id },
                apply(old, result.ops, new).map { it.id },
                "iteration $iteration: applying the ops must reproduce the target",
            )
        }
    }

    @Test
    fun `randomized full shuffles of surviving ids reproduce the target`() {
        val rng = Random(715517)
        repeat(200) { iteration ->
            val size = rng.nextInt(2, 24)
            val old = (0 until size).map { entry("id$it") }
            val new = old.shuffled(rng)
            val result = diffStructure(
                old = old,
                new = new,
                opsEnabled = true,
                churnThreshold = 1000.0,
            )
            assertFalse(result.isFull)
            assertTrue(
                result.ops.all { it.kind == AppleVaultStructureOp.KIND_MOVE },
                "a pure permutation must produce only MOVE ops",
            )
            assertOpsOrdering(result.ops)
            assertEquals(
                new.map { it.id },
                apply(old, result.ops, new).map { it.id },
                "iteration $iteration: shuffle must apply cleanly",
            )
        }
    }
}
