package com.artemchep.keyguard.common.service.imports

import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * Every file of the [ImportCorpus] against its manifest row. The tests next
 * to this one check the items of each exporter's files in detail.
 */
class ImportCorpusTest {
    @Test
    fun `every corpus file imports as the manifest says`() = runTest {
        ImportCorpus.manifestRows().forEach { row ->
            val id = row.getValue("id")
            val result = ImportCorpus.parse(row)
            val expected = row.getValue("result")
            if (expected != ImportCorpus.RESULT_SUCCESS) {
                val failure = assertIs<ImportResult.Failure>(result, id)
                assertEquals(expected, failure.error.manifestName(), id)
                return@forEach
            }
            val plan = assertIs<ImportResult.Success>(result, "$id: $result").plan
            plan.assertVaultStorable()
            assertEquals(row.getValue("items").toInt(), plan.items.size, "$id items")
            assertEquals(row.getValue("folders").toInt(), plan.folders.size, "$id folders")
            val skips = parseSkips(row.getValue("skips"))
            ImportSkipReason.entries.forEach { reason ->
                assertEquals(skips[reason] ?: 0, plan.skips[reason], "$id skips of $reason")
            }
        }
    }

    @Test
    fun `password-protected files ask for their password and check it`() = runTest {
        ImportCorpus.manifestRows()
            .filter { it.getValue("password") != ImportCorpus.NONE }
            .forEach { row ->
                val id = row.getValue("id")
                assertEquals(
                    ImportResult.Failure(ImportError.PasswordRequired),
                    ImportCorpus.parse(row, password = null),
                    id,
                )
                assertEquals(
                    ImportResult.Failure(ImportError.WrongPassword),
                    ImportCorpus.parse(row, password = "not the password"),
                    id,
                )
            }
    }

    @Test
    fun `manifest, provenance and checksums cover every export and match its bytes`() {
        val manifest = ImportCorpus.manifestRows().map { it.getValue("resource") }.toSet()
        val provenance = ImportCorpus.tsv(ImportCorpus.PROVENANCE).map { it.getValue("resource") }.toSet()
        val checksums = ImportCorpus.text(ImportCorpus.CHECKSUMS)
            .lineSequence()
            .filter { it.isNotBlank() }
            .associate { line ->
                val (digest, resource) = line.split("  ", limit = 2)
                resource to digest
            }
        val root = ImportCorpus.root()
        val onDisk = ImportCorpus.EXPORT_DIRECTORIES
            .flatMap { directory ->
                Files.walk(root.resolve(directory)).use { paths ->
                    paths
                        .filter { it.isRegularFile() }
                        .map { it.relativeTo(root).toString().replace('\\', '/') }
                        .toList()
                }
            }
            .toSet()

        assertEquals(onDisk, manifest)
        assertEquals(onDisk, provenance)
        assertEquals(onDisk, checksums.keys)
        checksums.forEach { (resource, digest) ->
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(ImportCorpus.bytes(resource))
                .joinToString("") { "%02x".format(it) }
            assertEquals(digest, actual, "$resource changed; regenerate it as the corpus README says")
        }
    }
}

private fun parseSkips(value: String): Map<ImportSkipReason, Int> =
    if (value == ImportCorpus.NONE) {
        emptyMap()
    } else {
        value.split(',').associate { entry ->
            val (reason, count) = entry.split('=')
            ImportSkipReason.valueOf(reason) to count.toInt()
        }
    }

private fun ImportError.manifestName(): String = when (this) {
    ImportError.Parse -> "PARSE"
    ImportError.UnsupportedVersion -> "UNSUPPORTED_VERSION"
    ImportError.TooLarge -> "TOO_LARGE"
    ImportError.Unreadable -> "UNREADABLE"
    ImportError.PasswordRequired -> "PASSWORD_REQUIRED"
    ImportError.WrongPassword -> "WRONG_PASSWORD"
    ImportError.UnsupportedEncryption -> "UNSUPPORTED_ENCRYPTION"
}
