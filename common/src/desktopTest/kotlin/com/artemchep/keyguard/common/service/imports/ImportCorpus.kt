package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.Password
import java.nio.file.Path
import kotlin.test.assertIs
import kotlinx.io.readString

/**
 * The files of `resources/import-corpus`, written by real exporters, and what
 * its `manifest.tsv` says each must import as. The README there says how each
 * file was made and how to regenerate them.
 */
internal object ImportCorpus {
    const val NONE = "-"
    const val RESULT_SUCCESS = "SUCCESS"

    const val MANIFEST = "manifest.tsv"
    const val PROVENANCE = "provenance.tsv"
    const val CHECKSUMS = "checksums.sha256"
    val EXPORT_DIRECTORIES = listOf("bitwarden", "keepassxc", "keyguard")

    private const val ROOT = "import-corpus"

    private val service = importTestService()

    fun manifestRows(): List<Map<String, String>> = tsv(MANIFEST)

    fun manifestRow(id: String): Map<String, String> = manifestRows()
        .single { it.getValue("id") == id }

    fun tsv(path: String): List<Map<String, String>> {
        val lines = text(path).lines().filter { it.isNotBlank() }
        val header = lines.first().split('\t')
        return lines.drop(1).map { line ->
            val values = line.split('\t')
            require(values.size == header.size) { "Malformed $path line: $line" }
            header.zip(values).toMap()
        }
    }

    /**
     * Parses the file of the manifest [row] with its password, unless an
     * other [password] is given.
     */
    suspend fun parse(
        row: Map<String, String>,
        password: String? = row.getValue("password").takeUnless { it == NONE },
    ): ImportResult = service.parse(
        format = ImportFormatId.valueOf(row.getValue("format")),
        input = BytesImportInput(bytes(row.getValue("resource"))),
        password = password?.let(::Password),
        now = IMPORT_TEST_NOW,
    )

    suspend fun successPlan(id: String): ImportPlan {
        val result = parse(manifestRow(id))
        return assertIs<ImportResult.Success>(result, "$id: $result").plan
    }

    /**
     * The text of every one of the [refs] of the corpus file [id].
     */
    suspend fun extractAttachments(
        id: String,
        refs: Set<String>,
    ): Map<String, String> {
        val row = manifestRow(id)
        return service.extractAttachments(
            format = ImportFormatId.valueOf(row.getValue("format")),
            input = BytesImportInput(bytes(row.getValue("resource"))),
            password = row.getValue("password").takeUnless { it == NONE }?.let(::Password),
            refs = refs,
        ) { _, source -> source.readString() }
    }

    /** The directory of the corpus, for listing the files in it. */
    fun root(): Path = Path.of(
        requireNotNull(javaClass.classLoader.getResource("$ROOT/$MANIFEST")).toURI(),
    ).parent

    fun bytes(path: String): ByteArray = javaClass.classLoader
        .getResourceAsStream("$ROOT/$path")
        .let { requireNotNull(it) { "Missing corpus resource $path" } }
        .use { it.readBytes() }

    fun text(path: String): String = bytes(path).decodeToString()
}
