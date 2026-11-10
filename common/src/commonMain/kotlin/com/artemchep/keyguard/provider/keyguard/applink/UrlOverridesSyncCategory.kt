package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.model.DAppLinkSyncCategory
import com.artemchep.keyguard.common.util.int
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.p2psync.boolean
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/**
 * The URL overrides. The body of an entry is `{"name", "regex", "command", "enabled"}`: three
 * strings and a boolean; the creation date stays local. A body without them, or whose regex
 * does not compile on this platform, is not written.
 *
 * The built-in override has no sync id: every app adds its own, so it is never synced.
 */
internal class UrlOverridesSyncCategory : AppSyncCategory {
    companion object {
        private const val FIELD_NAME = "name"
        private const val FIELD_REGEX = "regex"
        private const val FIELD_COMMAND = "command"
        private const val FIELD_ENABLED = "enabled"
    }

    override val kind: EntryKind get() = EntryKind.URL_OVERRIDE

    override val category: DAppLinkSyncCategory get() = DAppLinkSyncCategory.URL_OVERRIDES

    override fun readIds(database: Database): Set<String> = database
        .urlOverrideQueries
        .getSyncIds()
        .executeAsList()
        .toSet()

    override fun read(database: Database, id: String): JsonObject? = database
        .urlOverrideQueries
        .getBySyncId(syncId = id)
        .executeAsOneOrNull()
        ?.let { row -> bodyOf(row.name, row.regex, row.command, row.enabled) }

    // An override is one row: an envelope bounds how many a pass writes.
    override fun write(
        database: Database,
        id: String,
        body: JsonObject,
        now: Instant,
        budget: AppSyncWriteBudget,
    ): Boolean {
        val name = body.string(FIELD_NAME)
        val rule = body.ruleOrNull()
        val enabled = body.boolean(FIELD_ENABLED)
        if (name == null || rule == null || enabled == null) return false
        val (regex, command) = rule
        val dao = database.urlOverrideQueries
        val exists = dao.getBySyncId(syncId = id).executeAsOneOrNull() != null
        if (exists) {
            dao.updateBySyncId(
                name = name,
                regex = regex,
                command = command,
                enabled = enabled.int.toLong(),
                syncId = id,
            )
        } else {
            dao.insert(
                name = name,
                regex = regex,
                command = command,
                createdAt = now,
                enabled = enabled.int.toLong(),
                syncId = id,
            )
        }
        return true
    }

    override fun delete(database: Database, id: String, budget: AppSyncWriteBudget) {
        database.urlOverrideQueries.deleteBySyncId(syncId = id)
    }

    override fun changeId(database: Database, id: String, newId: String) {
        database.urlOverrideQueries.updateSyncId(
            newSyncId = newId,
            syncId = id,
        )
    }

    private fun bodyOf(
        name: String,
        regex: String,
        command: String,
        enabled: Long,
    ): JsonObject = buildJsonObject {
        put(FIELD_NAME, name)
        put(FIELD_REGEX, regex)
        put(FIELD_COMMAND, command)
        put(FIELD_ENABLED, enabled != 0L)
    }

    /**
     * Returns the regex and the command of the body, or `null` if either is missing. The
     * overrides are matched by their regex, so one that does not compile on this platform
     * must not reach the list.
     */
    private fun JsonObject.ruleOrNull(): Pair<String, String>? {
        val regex = string(FIELD_REGEX)
            ?.takeIf { runCatching { Regex(it) }.isSuccess }
        val command = string(FIELD_COMMAND)
        return if (regex != null && command != null) regex to command else null
    }
}
