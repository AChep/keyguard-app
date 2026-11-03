package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.test.FakeSshKeyImportService
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.common.service.imports.impl.ImportServiceImpl
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.crypto.NativeCipherEncryptor
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.feature.environments.isWritable
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.io.Buffer
import kotlinx.io.Source

internal val IMPORT_TEST_NOW = Instant.fromEpochSeconds(1_700_000_000)

/**
 * An in-memory file to import.
 */
internal class BytesImportInput(
    private val bytes: ByteArray,
    override val name: String? = null,
) : ImportInput {
    constructor(text: String) : this(text.encodeToByteArray())

    override fun open(): Source = Buffer().apply {
        write(bytes)
    }
}

internal fun importTestService(
    sshKeyImportService: SshKeyImportService = FakeSshKeyImportService(),
) = ImportServiceImpl(
    cryptoGenerator = NativeCryptoGenerator(),
    cipherEncryptor = NativeCipherEncryptor(
        cryptoGenerator = NativeCryptoGenerator(),
        base64Service = Base64ServiceImpl(),
    ),
    sshKeyImportService = sshKeyImportService,
)

/**
 * Opens [input] as [format] and parses its only snapshot.
 */
internal suspend fun ImportService.parse(
    format: ImportFormatId,
    input: ImportInput,
    password: Password? = null,
    now: Instant,
): ImportResult = open(format, input, password)
    .parse(snapshotId = null, now = now)

/**
 * Opens [input] as [format] and extracts the [refs] of its plan.
 */
internal suspend fun <T> ImportService.extractAttachments(
    format: ImportFormatId,
    input: ImportInput,
    password: Password?,
    refs: Set<String>,
    sink: ImportAttachmentSink<T>,
): Map<String, T> = open(format, input, password)
    .extract(refs, sink)

internal suspend fun parseImport(
    format: ImportFormatId,
    text: String,
    password: String? = null,
    sshKeyImportService: SshKeyImportService = FakeSshKeyImportService(),
): ImportResult = importTestService(sshKeyImportService).parse(
    format = format,
    input = BytesImportInput(text),
    password = password?.let(::Password),
    now = IMPORT_TEST_NOW,
)

internal suspend fun parseImportPlan(
    format: ImportFormatId,
    text: String,
    password: String? = null,
    sshKeyImportService: SshKeyImportService = FakeSshKeyImportService(),
): ImportPlan {
    val result = parseImport(format, text, password, sshKeyImportService)
    return assertIs<ImportResult.Success>(result).plan
}

/**
 * The rules the vault enforces on a create request, which a planned item must
 * already satisfy: no ownership (the commit fills it in), the flags the vault
 * rejects a request without, a concrete type, and an environment it writes.
 */
internal fun assertVaultStorable(
    request: CreateRequest,
) {
    assertNull(request.ownership)
    assertNull(request.ownership2)
    assertNotNull(request.favorite)
    assertNotNull(request.reprompt)
    assertNotNull(request.type)
    assertEquals(IMPORT_TEST_NOW, request.now)
    request.fields.forEach { field ->
        assertNotNull(field.name)
    }
    if (request.type == DSecret.Type.Environment) {
        assertTrue(assertNotNull(request.environment).isWritable())
    }
}

internal fun ImportPlan.assertVaultStorable() {
    items.forEach { item ->
        assertVaultStorable(item.request)
    }
    val keys = folders.map { it.key }.toSet()
    items.forEach { item ->
        item.folderKey?.let { key ->
            assertTrue(key in keys, "Unknown folder key $key")
        }
    }
    // Parents precede their children.
    val seen = mutableSetOf<String>()
    folders.forEach { folder ->
        folder.parentKey?.let { parent ->
            assertTrue(parent in seen, "Folder ${folder.key} precedes its parent")
        }
        seen += folder.key
    }
}
