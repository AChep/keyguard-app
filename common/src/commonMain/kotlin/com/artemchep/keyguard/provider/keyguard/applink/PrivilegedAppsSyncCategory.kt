package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.model.DAppLinkSyncCategory
import com.artemchep.keyguard.common.model.isPrivilegedAppValid
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.data.PrivilegedApp
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/**
 * The privileged apps the user added; the ones that come with the app never sync. The body
 * of an entry is `{"packageName", "certFingerprintSha256"}`: two strings; the name and the
 * creation date stay local. A body without them, or with a package or a fingerprint the
 * list would not hold, see [isPrivilegedAppValid], is not written.
 */
internal class PrivilegedAppsSyncCategory : AppSyncCategory {
    companion object {
        private const val FIELD_PACKAGE_NAME = "packageName"
        private const val FIELD_CERT_FINGERPRINT_SHA256 = "certFingerprintSha256"
    }

    override val kind: EntryKind get() = EntryKind.PRIVILEGED_APP

    override val category: DAppLinkSyncCategory get() = DAppLinkSyncCategory.PRIVILEGED_APPS

    override fun readIds(database: Database): Set<String> = database
        .privilegedAppQueries
        .getSyncIds()
        .executeAsList()
        .toSet()

    override fun read(database: Database, id: String): JsonObject? = database
        .privilegedAppQueries
        .getBySyncId(syncId = id)
        .executeAsOneOrNull()
        ?.toBody()

    // An app is one row: an envelope bounds how many a pass writes.
    override fun write(
        database: Database,
        id: String,
        body: JsonObject,
        now: Instant,
        budget: AppSyncWriteBudget,
    ): AppSyncWrite {
        val packageName = body.string(FIELD_PACKAGE_NAME)
        val certFingerprintSha256 = body.string(FIELD_CERT_FINGERPRINT_SHA256)
        // The apps on the list may request passkeys for any website, so one that Android
        // could not match must not reach it.
        if (packageName == null ||
            certFingerprintSha256 == null ||
            !isPrivilegedAppValid(packageName, certFingerprintSha256)
        ) {
            return AppSyncWrite.REFUSED
        }
        val dao = database.privilegedAppQueries
        return writeRow(
            update = {
                dao.updateBySyncId(
                    packageName = packageName,
                    certFingerprintSha256 = certFingerprintSha256,
                    syncId = id,
                ).value
            },
            insert = {
                dao.insert(
                    name = null,
                    packageName = packageName,
                    certFingerprintSha256 = certFingerprintSha256,
                    createdAt = now,
                    syncId = id,
                )
            },
        )
    }

    override fun delete(database: Database, id: String, budget: AppSyncWriteBudget) {
        database.privilegedAppQueries.deleteBySyncId(syncId = id)
    }

    override fun changeId(database: Database, id: String, newId: String) {
        database.privilegedAppQueries.updateSyncId(
            newSyncId = newId,
            syncId = id,
        )
    }

    private fun PrivilegedApp.toBody(): JsonObject = buildJsonObject {
        put(FIELD_PACKAGE_NAME, packageName)
        put(FIELD_CERT_FINGERPRINT_SHA256, certFingerprintSha256)
    }
}
