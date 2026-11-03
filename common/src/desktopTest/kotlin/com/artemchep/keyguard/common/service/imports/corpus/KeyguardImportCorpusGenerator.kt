package com.artemchep.keyguard.common.service.imports.corpus

import com.artemchep.keyguard.common.service.export.impl.JsonExportServiceImpl
import com.artemchep.keyguard.common.service.serialization.createApplicationJson
import com.artemchep.keyguard.util.zip.ZipConfig
import com.artemchep.keyguard.util.zip.ZipEntry
import com.artemchep.keyguard.util.zip.createZipService
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.writeString

/**
 * Writes `keyguard/export.zip` of the import corpus: the
 * [KeyguardImportCorpusSeed] exported by today's Keyguard exporter, frozen so
 * that later importer changes are tested against files older versions wrote.
 *
 * Not a test. It runs only through `./gradlew :common:importCorpusKeyguard`,
 * which excludes it from `desktopTest` and passes the output directory.
 */
class KeyguardImportCorpusGenerator {
    companion object {
        /** Public test material; see the corpus manifest. */
        const val PASSWORD = "keyguard-corpus-password"

        private const val OUTPUT_PROPERTY = "keyguard.import-corpus.output"
        private const val VERSION_PROPERTY = "keyguard.import-corpus.version"

        private const val RESOURCE = "keyguard/export.zip"
    }

    @Test
    fun generate() = runTest {
        val root = System.getProperty(OUTPUT_PROPERTY)
            ?.let { Path.of(it) }
            ?: error("Run through ./gradlew :common:importCorpusKeyguard, which sets $OUTPUT_PROPERTY.")
        val version = System.getProperty(VERSION_PROPERTY)
            ?: error("$VERSION_PROPERTY is not set.")

        val json = JsonExportServiceImpl(createApplicationJson()).export(
            organizations = emptyList(),
            collections = emptyList(),
            folders = KeyguardImportCorpusSeed.folders(),
            ciphers = KeyguardImportCorpusSeed.ciphers(),
        )
        val output = root.resolve(RESOURCE)
        Files.createDirectories(output.parent)
        Files.write(output, zip(json))

        writeProvenance(
            root = root,
            row = listOf(RESOURCE, "keyguard", version, "-", LocalDate.now(ZoneOffset.UTC).toString()),
        )
    }

    /**
     * The archive as `ExportManagerImpl` writes it: the vault, then every
     * attachment under `attachments/<attachment id>/<file name>`.
     */
    private suspend fun zip(json: String): ByteArray {
        val attachment = with(KeyguardImportCorpusSeed) {
            "attachments/$ATTACHMENT_ID/$ATTACHMENT_NAME" to ATTACHMENT_TEXT
        }
        val entries = listOf("vault.json" to json, attachment)
        val buffer = Buffer()
        createZipService().zip(
            outputStream = buffer,
            config = ZipConfig(
                encryption = ZipConfig.Encryption(password = PASSWORD),
            ),
            entries = entries.map { (name, text) ->
                ZipEntry(
                    name = name,
                    data = ZipEntry.Data.In {
                        Buffer().apply { writeString(text) } as Source
                    },
                )
            },
        )
        return buffer.readByteArray()
    }
}

private const val PROVENANCE_FILE = "provenance.tsv"
private const val PROVENANCE_HEADER = "resource\texporter\texporter_version\tserver\tgenerated"

/**
 * Replaces the row of the resource in `provenance.tsv`, keeping the rows
 * other generators wrote.
 */
private fun writeProvenance(
    root: Path,
    row: List<String>,
) {
    val file = root.resolve(PROVENANCE_FILE)
    val rows = if (Files.exists(file)) {
        Files.readAllLines(file)
            .drop(1)
            .filter { it.isNotBlank() }
            .map { it.split('\t') }
    } else {
        emptyList()
    }
    val updated = (rows.filterNot { it.first() == row.first() } + listOf(row))
        .sortedBy { it.first() }
    val text = (listOf(PROVENANCE_HEADER) + updated.map { it.joinToString("\t") })
        .joinToString(separator = "\n", postfix = "\n")
    Files.writeString(file, text)
}
