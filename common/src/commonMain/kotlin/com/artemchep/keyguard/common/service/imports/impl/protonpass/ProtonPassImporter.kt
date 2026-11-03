package com.artemchep.keyguard.common.service.imports.impl.protonpass

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.common.service.imports.ImportError
import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.ImportFormatId
import com.artemchep.keyguard.common.service.imports.ImportInput
import com.artemchep.keyguard.common.service.imports.ImportPlan
import com.artemchep.keyguard.common.service.imports.ImportResult
import com.artemchep.keyguard.common.service.imports.ImportSkipReason
import com.artemchep.keyguard.common.service.imports.impl.IMPORT_EXTENSION_CSV
import com.artemchep.keyguard.common.service.imports.impl.ImportPlanBuilder
import com.artemchep.keyguard.common.service.imports.impl.Importer
import com.artemchep.keyguard.common.service.imports.impl.decodeFromJsonElement
import com.artemchep.keyguard.common.service.imports.impl.importJson
import com.artemchep.keyguard.common.service.imports.impl.importedFolderPath
import com.artemchep.keyguard.common.service.imports.impl.isZipArchive
import com.artemchep.keyguard.common.service.imports.impl.readUnencryptedArchiveEntries
import com.artemchep.keyguard.common.service.imports.impl.readText
import com.artemchep.keyguard.util.io.decodeTextDetectingEncoding
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Proton Pass, in each of the forms its export takes:
 * - a ZIP archive with `Proton Pass/data.json`, and attachments under
 *   `Proton Pass/files/`;
 * - that JSON on its own;
 * - a CSV of `type,name,url,…,vault`, which carries logins in full and
 *   every other item type only as its note.
 *
 * Each vault becomes a folder, with the vault's own folders nested in it.
 * A PGP-encrypted export — `Proton Pass/data.pgp` inside the archive, or a
 * whole archive armored as one message — cannot be read here.
 */
internal class ProtonPassImporter(
    private val sshKeyImportService: SshKeyImportService,
) : Importer {
    companion object {
        private const val DATA_JSON = "data.json"
        private const val DATA_PGP = "data.pgp"
        private const val FILES_DIRECTORY = "/files/"
        private const val PGP_ARMOR = "-----BEGIN PGP MESSAGE-----"
    }

    override val format = ImportFormat(
        id = ImportFormatId.ProtonPass,
        title = "Proton Pass",
        extensions = listOf("zip", "json", IMPORT_EXTENSION_CSV),
    )

    private val csv = ProtonPassCsvImporter(
        format = format,
    )

    @Serializable
    private data class Vault(
        val name: String? = null,
        val folders: List<Folder> = emptyList(),
        val items: List<JsonElement> = emptyList(),
    )

    @Serializable
    private data class Folder(
        val folderId: String? = null,
        val parentFolderId: String? = null,
        val name: String? = null,
    )

    private sealed interface Source {
        data class Json(val text: String) : Source

        data class Csv(val text: String) : Source

        data object Encrypted : Source

        data object Missing : Source
    }

    override fun parse(
        input: ImportInput,
        password: Password?,
        now: Instant,
    ): ImportResult = when (val source = readSource(input)) {
        Source.Encrypted -> ImportResult.Failure(ImportError.UnsupportedEncryption)
        Source.Missing -> ImportResult.Failure(ImportError.Parse)
        is Source.Csv -> csv.parseText(source.text, now)
        is Source.Json -> {
            val root = importJson.parseToJsonElement(source.text) as? JsonObject
            // Not the `encrypted` member: a legacy export the user decrypted
            // still says `true`.
            val vaults = root?.get("vaults") as? JsonObject
            if (vaults != null) {
                ImportResult.Success(parseVaults(vaults, now))
            } else {
                ImportResult.Failure(ImportError.Parse)
            }
        }
    }

    private fun readSource(
        input: ImportInput,
    ): Source {
        if (input.isZipArchive()) {
            val entries = input.readUnencryptedArchiveEntries(accept = ::isDataEntry)
            val json = entries.entries.firstOrNull { it.key.substringAfterLast('/') == DATA_JSON }
            return when {
                entries.keys.any { it.substringAfterLast('/') == DATA_PGP } -> Source.Encrypted
                json != null -> Source.Json(decodeTextDetectingEncoding(json.value))
                else -> Source.Missing
            }
        }
        val text = input.readText()
        val head = text.trimStart()
        return when {
            head.startsWith(PGP_ARMOR) -> Source.Encrypted
            head.startsWith("{") -> Source.Json(text)
            else -> Source.Csv(text)
        }
    }

    /**
     * The export's data file; an attachment of the same name is not.
     */
    private fun isDataEntry(
        name: String,
    ): Boolean {
        val base = name.substringAfterLast('/')
        return (base == DATA_JSON || base == DATA_PGP) &&
            FILES_DIRECTORY !in "/$name" &&
            !name.startsWith("__MACOSX/")
    }

    private fun parseVaults(
        vaults: JsonObject,
        now: Instant,
    ): ImportPlan {
        val plan = ImportPlanBuilder(format)
        vaults.values.forEach { element ->
            val vault = runCatchingNonFatal {
                importJson.decodeFromJsonElement<Vault>(element)
            }.getOrNull()
            if (vault == null) {
                plan.skip(ImportSkipReason.Collection)
                return@forEach
            }
            val foldersById = vault.folders
                .filter { it.folderId != null }
                .associateBy { it.folderId!! }
            vault.items.forEach { itemElement ->
                val title = runCatchingNonFatal {
                    importJson.decodeFromJsonElement<ProtonPassItem>(itemElement).data?.metadata?.name
                }.getOrNull()
                plan.record(title) {
                    val item = importJson.decodeFromJsonElement<ProtonPassItem>(itemElement)
                    val folderPath = item.folderId
                        ?.let { id ->
                            importedFolderPath(
                                id = id,
                                parentOf = { foldersById[it]?.parentFolderId },
                                titleOf = { foldersById[it]?.name },
                            )
                        }
                        .orEmpty()
                    val folderKey = plan.folder(listOfNotNull(vault.name) + folderPath)
                    mapProtonPassItem(item, now, folderKey, plan, sshKeyImportService)
                }
            }
        }
        return plan.build()
    }
}
