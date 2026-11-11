package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.model.DAppLinkSyncCategory
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.data.GeneratorEmailRelay
import com.artemchep.keyguard.feature.p2psync.P2pJson
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/**
 * The email forwarders of the password generator. The body of an entry is
 * `{"name", "type", "data": {key: value}}`, all strings; the creation date stays local. A
 * body without them is not written.
 */
internal class EmailForwardersSyncCategory : AppSyncCategory {
    companion object {
        private const val FIELD_NAME = "name"
        private const val FIELD_TYPE = "type"
        private const val FIELD_DATA = "data"
    }

    override val kind: EntryKind get() = EntryKind.EMAIL_FORWARDER

    override val category: DAppLinkSyncCategory get() = DAppLinkSyncCategory.EMAIL_FORWARDERS

    override fun readIds(database: Database): Set<String> = database
        .generatorEmailRelayQueries
        .getSyncIds()
        .executeAsList()
        .toSet()

    override fun read(database: Database, id: String): JsonObject? = database
        .generatorEmailRelayQueries
        .getBySyncId(syncId = id)
        .executeAsOneOrNull()
        ?.toBody()

    // A forwarder is one row: an envelope bounds how many a pass writes.
    override fun write(
        database: Database,
        id: String,
        body: JsonObject,
        now: Instant,
        budget: AppSyncWriteBudget,
    ): AppSyncWrite {
        val name = body.string(FIELD_NAME)
        val type = body.string(FIELD_TYPE)
        val data = (body[FIELD_DATA] as? JsonObject)
            ?.takeIf { data -> data.values.all { (it as? JsonPrimitive)?.isString == true } }
        if (name == null || type.isNullOrBlank() || data == null) return AppSyncWrite.REFUSED
        val dataText = P2pJson.encodeToString(JsonObject.serializer(), data)
        val dao = database.generatorEmailRelayQueries
        return writeRow(
            // In place: the generator refers to a forwarder by its row.
            update = {
                dao.updateBySyncId(
                    name = name,
                    type = type,
                    data = dataText,
                    syncId = id,
                ).value
            },
            insert = {
                dao.insert(
                    name = name,
                    type = type,
                    data = dataText,
                    createdAt = now,
                    syncId = id,
                )
            },
        )
    }

    override fun delete(database: Database, id: String, budget: AppSyncWriteBudget) {
        database.generatorEmailRelayQueries.deleteBySyncId(syncId = id)
    }

    override fun changeId(database: Database, id: String, newId: String) {
        database.generatorEmailRelayQueries.updateSyncId(
            newSyncId = newId,
            syncId = id,
        )
    }

    private fun GeneratorEmailRelay.toBody(): JsonObject = buildJsonObject {
        put(FIELD_NAME, name)
        put(FIELD_TYPE, type)
        val data = runCatching { P2pJson.parseToJsonElement(data_) as? JsonObject }
            .getOrNull()
            ?: JsonObject(emptyMap())
        put(FIELD_DATA, data)
    }
}
