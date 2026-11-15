package com.artemchep.keyguard.feature.home.vault.add

import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportComponent
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportError
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportResult
import com.artemchep.keyguard.test.generatedGpgKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GpgKeyPassphraseImportTest {
    private val key = generatedGpgKey()

    @Test
    fun `step one passphrase imports the key`() = runTest {
        val run = ImportRun(
            importResults = listOf(GpgKeyImportResult.Success(key)),
            passphrases = listOf("secret"),
        )

        run.execute()

        assertEquals(listOf(listOf("secret")), run.importRequests)
        assertEquals(listOf(false), run.passphrasePrompts)
        assertEquals(key, run.success)
        assertNull(run.error)
    }

    @Test
    fun `step one typo asks again with an error`() = runTest {
        val run = ImportRun(
            importResults = listOf(
                GpgKeyImportResult.Error(GpgKeyImportError.InvalidPassphrase),
                GpgKeyImportResult.Success(key),
            ),
            passphrases = listOf("secert", "secret"),
        )

        run.execute()

        assertEquals(listOf(false, true), run.passphrasePrompts)
        assertEquals(listOf(listOf("secert"), listOf("secret")), run.importRequests)
        assertEquals(key, run.success)
    }

    @Test
    fun `partial unlock asks for the locked components`() = runTest {
        val run = ImportRun(
            importResults = listOf(
                needsPassphrase(listOf(SUBKEY_A), unlocking = setOf(0)),
                GpgKeyImportResult.Success(key),
            ),
            passphrases = listOf("primary"),
            componentAnswers = listOf(mapOf(SUBKEY_A.fingerprint to "subkey")),
        )

        run.execute()

        assertEquals(
            listOf(ComponentPrompt(listOf(SUBKEY_A), emptySet())),
            run.componentPrompts,
        )
        assertEquals(listOf(listOf("primary"), listOf("primary", "subkey")), run.importRequests)
        assertEquals(key, run.success)
    }

    @Test
    fun `retry shows only still locked components and marks filled ones invalid`() = runTest {
        val run = ImportRun(
            importResults = listOf(
                needsPassphrase(listOf(SUBKEY_A, SUBKEY_B), unlocking = setOf(0)),
                // "a-wrong" unlocked nothing, "b" unlocked subkey B.
                needsPassphrase(listOf(SUBKEY_A), unlocking = setOf(0, 2)),
                GpgKeyImportResult.Success(key),
            ),
            passphrases = listOf("primary"),
            componentAnswers = listOf(
                mapOf(SUBKEY_A.fingerprint to "a-wrong", SUBKEY_B.fingerprint to "b"),
                mapOf(SUBKEY_A.fingerprint to "a"),
            ),
        )

        run.execute()

        assertEquals(
            listOf(
                ComponentPrompt(listOf(SUBKEY_A, SUBKEY_B), emptySet()),
                ComponentPrompt(listOf(SUBKEY_A), setOf(SUBKEY_A.fingerprint)),
            ),
            run.componentPrompts,
        )
        assertEquals(
            listOf(
                listOf("primary"),
                listOf("primary", "a-wrong", "b"),
                // The passphrase that unlocked nothing is not carried forward.
                listOf("primary", "b", "a"),
            ),
            run.importRequests,
        )
        assertEquals(key, run.success)
    }

    @Test
    fun `shared and empty answers are de-duplicated`() = runTest {
        val run = ImportRun(
            importResults = listOf(
                needsPassphrase(listOf(SUBKEY_A, SUBKEY_B, SUBKEY_C), unlocking = setOf(0)),
                GpgKeyImportResult.Success(key),
            ),
            passphrases = listOf("primary"),
            componentAnswers = listOf(
                mapOf(
                    SUBKEY_A.fingerprint to "shared",
                    SUBKEY_B.fingerprint to "shared",
                    SUBKEY_C.fingerprint to "",
                ),
            ),
        )

        run.execute()

        assertEquals(listOf(listOf("primary"), listOf("primary", "shared")), run.importRequests)
        assertEquals(key, run.success)
    }

    @Test
    fun `answers with nothing new ask again without importing`() = runTest {
        val run = ImportRun(
            importResults = listOf(
                needsPassphrase(listOf(SUBKEY_A, SUBKEY_B), unlocking = setOf(0)),
                GpgKeyImportResult.Success(key),
            ),
            passphrases = listOf("primary"),
            componentAnswers = listOf(
                // Re-entering a passphrase that was already tried is not new.
                mapOf(SUBKEY_A.fingerprint to "primary", SUBKEY_B.fingerprint to ""),
                mapOf(SUBKEY_A.fingerprint to "a", SUBKEY_B.fingerprint to "b"),
            ),
        )

        run.execute()

        assertEquals(
            listOf(
                ComponentPrompt(listOf(SUBKEY_A, SUBKEY_B), emptySet()),
                ComponentPrompt(listOf(SUBKEY_A, SUBKEY_B), setOf(SUBKEY_A.fingerprint)),
            ),
            run.componentPrompts,
        )
        assertEquals(listOf(listOf("primary"), listOf("primary", "a", "b")), run.importRequests)
        assertEquals(key, run.success)
    }

    @Test
    fun `cancelling step one stops silently`() = runTest {
        val run = ImportRun(
            importResults = emptyList(),
            passphrases = listOf(null),
        )

        run.execute()

        assertEquals(emptyList(), run.importRequests)
        assertNull(run.success)
        assertNull(run.error)
    }

    @Test
    fun `cancelling the step one retry stops silently`() = runTest {
        val run = ImportRun(
            importResults = listOf(GpgKeyImportResult.Error(GpgKeyImportError.InvalidPassphrase)),
            passphrases = listOf("secert", null),
        )

        run.execute()

        assertEquals(listOf(false, true), run.passphrasePrompts)
        assertNull(run.success)
        assertNull(run.error)
    }

    @Test
    fun `cancelling step two stops silently`() = runTest {
        val run = ImportRun(
            importResults = listOf(needsPassphrase(listOf(SUBKEY_A), unlocking = setOf(0))),
            passphrases = listOf("primary"),
            componentAnswers = listOf(null),
        )

        run.execute()

        assertEquals(1, run.componentPrompts.size)
        assertEquals(listOf(listOf("primary")), run.importRequests)
        assertNull(run.success)
        assertNull(run.error)
    }

    @Test
    fun `other errors are reported`() = runTest {
        val run = ImportRun(
            importResults = listOf(GpgKeyImportResult.Error(GpgKeyImportError.MalformedKey)),
            passphrases = listOf("secret"),
        )

        run.execute()

        assertEquals(GpgKeyImportError.MalformedKey, run.error)
    }
}

private val SUBKEY_A = component("A")
private val SUBKEY_B = component("B")
private val SUBKEY_C = component("C")

private fun component(suffix: String) = GpgKeyImportComponent(
    fingerprint = "FINGERPRINT$suffix",
    keyId = "KEYID$suffix",
    primary = false,
)

private fun needsPassphrase(
    locked: List<GpgKeyImportComponent>,
    unlocking: Set<Int>,
) = GpgKeyImportResult.NeedsPassphrase(
    formatLabel = "OpenPGP",
    lockedComponents = locked,
    unlockingPassphraseIndexes = unlocking,
)

private data class ComponentPrompt(
    val components: List<GpgKeyImportComponent>,
    val invalidFingerprints: Set<String>,
)

/** Scripted user answers and importer results; fails on any unexpected call. */
private class ImportRun(
    importResults: List<GpgKeyImportResult>,
    passphrases: List<String?>,
    componentAnswers: List<Map<String, String>?> = emptyList(),
) {
    private val importResults = ArrayDeque(importResults)
    private val passphrases = ArrayDeque(passphrases)
    private val componentAnswers = ArrayDeque(componentAnswers)

    val importRequests = mutableListOf<List<String>>()
    val passphrasePrompts = mutableListOf<Boolean>()
    val componentPrompts = mutableListOf<ComponentPrompt>()
    var success: GeneratedGpgKey? = null
    var error: GpgKeyImportError? = null

    suspend fun execute() {
        importGpgKeyWithPassphrases(
            initial = GpgKeyImportResult.NeedsPassphrase("OpenPGP"),
            importGpgKey = { passphrases ->
                importRequests += passphrases
                importResults.removeFirst()
            },
            promptPassphrase = { formatLabel, invalid ->
                assertEquals("OpenPGP", formatLabel)
                passphrasePrompts += invalid
                passphrases.removeFirst()
            },
            promptComponentPassphrases = { components, invalidFingerprints ->
                componentPrompts += ComponentPrompt(components, invalidFingerprints)
                componentAnswers.removeFirst()
            },
            onSuccess = { success = it },
            onError = { error = it },
        )
        assertEquals(0, importResults.size, "Unused import results")
        assertEquals(0, passphrases.size, "Unused passphrase answers")
        assertEquals(0, componentAnswers.size, "Unused component answers")
    }
}
