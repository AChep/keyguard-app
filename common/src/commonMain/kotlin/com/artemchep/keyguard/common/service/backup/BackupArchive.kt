package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.archive.readAgeArchive
import com.artemchep.keyguard.common.service.archive.writeAgeArchive
import com.artemchep.keyguard.util.age.AgePassword
import com.artemchep.keyguard.util.age.AgeSecret
import com.artemchep.keyguard.util.io.toSource
import com.artemchep.keyguard.util.zip.ZipConfig
import com.artemchep.keyguard.util.zip.ZipEntry
import com.artemchep.keyguard.util.zip.ZipReader
import com.artemchep.keyguard.util.zip.ZipService
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.Source

internal val BackupObjectKey.isAge: Boolean get() = value.endsWith(AGE_SUFFIX)

/** The password as an age secret; runs scrypt on every use. */
internal fun Password?.toAgeSecret(): AgeSecret? = this?.value?.takeIf { it.isNotEmpty() }?.let(::AgePassword)

/**
 * Opens [source], the object at [key], as a ZIP with [password] or, for an
 * `.age` key, as an age-encrypted ZIP with [ageSecret]. Takes ownership of [source].
 */
internal suspend fun openBackupArchive(
    key: BackupObjectKey,
    source: Source,
    password: Password?,
    ageSecret: AgeSecret? = password.toAgeSecret(),
    maxEntryBytes: Long = Long.MAX_VALUE,
): ZipReader {
    if (!key.isAge) {
        return ZipReader(source, password?.value, maxEntries = Int.MAX_VALUE, maxEntryBytes = maxEntryBytes)
    }
    val context = currentCoroutineContext()
    return readAgeArchive(source, ageSecret, maxEntryBytes) { context.ensureActive() }
}

/**
 * Writes [entries] to [key] as a ZIP with [password] or, for an `.age` key, as
 * an age-encrypted ZIP with [ageSecret].
 */
@Suppress("LongParameterList")
internal suspend fun BackupObjectStore.writeBackupArchive(
    zipService: ZipService,
    key: BackupObjectKey,
    mode: BackupWriteMode,
    password: Password?,
    entries: List<ZipEntry>,
    ageSecret: AgeSecret? = password.toAgeSecret(),
): BackupObjectInfo = write(key = key, mode = mode) { sink ->
    if (key.isAge) {
        writeAgeArchive(sink, requireNotNull(ageSecret), entries)
    } else {
        zipService.zip(outputStream = sink, config = zipConfig(password), entries = entries)
    }
}.requireAtomicCleanupComplete()

/** The archive options an object of this repository is written with. */
private fun zipConfig(
    password: Password?,
): ZipConfig = ZipConfig(
    encryption = password?.value
        ?.takeIf { it.isNotEmpty() }
        ?.let(ZipConfig::Encryption),
)

/** An entry holding a short, already materialised text payload. */
internal fun textEntry(
    name: String,
    text: String,
): ZipEntry = ZipEntry(
    name = name,
    data = ZipEntry.Data.In { text.toSource() },
)
