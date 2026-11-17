package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.model.DAppLinkSyncCategory
import com.artemchep.keyguard.common.model.MatchDetection
import com.artemchep.keyguard.common.model.UrlBlockScope
import com.artemchep.keyguard.common.model.isUrlBlockUriValid
import com.artemchep.keyguard.common.util.int
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.data.UrlBlock
import com.artemchep.keyguard.feature.p2psync.boolean
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/**
 * The blocked URIs of autofill. The body of an entry is `{"name", "description", "uri",
 * "mode", "enabled", "exposed"}`: four strings and two booleans; the mode is a
 * [MatchDetection.key], and the creation date stays local. A body without them, with an
 * unknown mode, or with a URI the list would refuse, see [isUrlBlockUriValid], is not
 * written.
 *
 * A rule that blocks only saving also has `"scope": "save"`, see [UrlBlockScope.key]; a rule
 * that blocks everything has no scope, so its body is the one of the builds before scopes. A
 * scope that is missing, unknown or not a string blocks everything. The scope has no part of
 * its own: it merges with the rest of the body, see
 * [com.artemchep.keyguard.feature.p2psync.parts.KeyguardPartSchemas.urlBlock].
 *
 * An older build reads only the fields it knows, so it blocks such a rule everywhere, and
 * keeps the scope on its own edits: its [AppSyncRecorderImpl] keeps the fields of the
 * current body that it did not read. A row it wrote keeps blocking everything after the
 * update, as the new column defaults to `all`, until the rule changes again.
 */
internal class UrlBlocksSyncCategory : AppSyncCategory {
    companion object {
        private const val FIELD_NAME = "name"
        private const val FIELD_DESCRIPTION = "description"
        private const val FIELD_URI = "uri"
        private const val FIELD_MODE = "mode"
        private const val FIELD_ENABLED = "enabled"
        private const val FIELD_EXPOSED = "exposed"
        private const val FIELD_SCOPE = "scope"
    }

    override val kind: EntryKind get() = EntryKind.URL_BLOCK

    override val category: DAppLinkSyncCategory get() = DAppLinkSyncCategory.URL_BLOCKS

    override fun readIds(database: Database): Set<String> = database
        .urlBlockQueries
        .getSyncIds()
        .executeAsList()
        .toSet()

    override fun read(database: Database, id: String): JsonObject? = database
        .urlBlockQueries
        .getBySyncId(syncId = id)
        .executeAsOneOrNull()
        ?.toBody()

    // A rule is one row: an envelope bounds how many a pass writes.
    override fun write(
        database: Database,
        id: String,
        body: JsonObject,
        now: Instant,
        budget: AppSyncWriteBudget,
    ): AppSyncWrite {
        val name = body.string(FIELD_NAME)
        val description = body.string(FIELD_DESCRIPTION)
        val rule = body.ruleOrNull()
        if (name == null || description == null || rule == null) return AppSyncWrite.REFUSED
        val scope = body.scope().key
        val dao = database.urlBlockQueries
        return writeRow(
            update = {
                dao.updateBySyncId(
                    name = name,
                    description = description,
                    uri = rule.uri,
                    mode = rule.mode.key,
                    enabled = rule.enabled.int.toLong(),
                    exposed = rule.exposed.int.toLong(),
                    scope = scope,
                    syncId = id,
                ).value
            },
            insert = {
                dao.insert(
                    name = name,
                    description = description,
                    uri = rule.uri,
                    mode = rule.mode.key,
                    createdAt = now,
                    enabled = rule.enabled.int.toLong(),
                    exposed = rule.exposed.int.toLong(),
                    syncId = id,
                    scope = scope,
                )
            },
        )
    }

    override fun delete(database: Database, id: String, budget: AppSyncWriteBudget) {
        database.urlBlockQueries.deleteBySyncId(syncId = id)
    }

    override fun changeId(database: Database, id: String, newId: String) {
        database.urlBlockQueries.updateSyncId(
            newSyncId = newId,
            syncId = id,
        )
    }

    // A rule without the exposed flag predates it: it is exposed.
    private fun UrlBlock.toBody(): JsonObject = buildJsonObject {
        put(FIELD_NAME, name)
        put(FIELD_DESCRIPTION, description)
        put(FIELD_URI, uri)
        put(FIELD_MODE, mode)
        put(FIELD_ENABLED, enabled != 0L)
        put(FIELD_EXPOSED, exposed != 0L)
        // A rule that blocks everything keeps the body of the builds before scopes.
        val ruleScope = UrlBlockScope.ofKey(scope)
        if (ruleScope != UrlBlockScope.ALL) put(FIELD_SCOPE, ruleScope.key)
    }

    /** The scope of the rule of the body: one that is missing, unknown or not a string blocks everything. */
    private fun JsonObject.scope(): UrlBlockScope = string(FIELD_SCOPE)
        ?.let(UrlBlockScope::ofKey)
        ?: UrlBlockScope.ALL

    /**
     * Returns the rule of the body, or `null` if a field is missing, the mode is unknown,
     * or the URI is blank or, in the regex mode, does not compile on this platform. The
     * autofill matches by them, so such a rule must not reach the list.
     */
    private fun JsonObject.ruleOrNull(): Rule? {
        val uri = string(FIELD_URI)
        val mode = string(FIELD_MODE)?.let { MatchDetection.ofKey(it) }
        if (uri == null || mode == null || !isUrlBlockUriValid(uri, mode)) return null
        val enabled = boolean(FIELD_ENABLED)
        val exposed = boolean(FIELD_EXPOSED)
        return if (enabled != null && exposed != null) Rule(uri, mode, enabled, exposed) else null
    }

    /** The URI of a rule, the mode that matches it, and its switches. */
    private class Rule(
        val uri: String,
        val mode: MatchDetection,
        val enabled: Boolean,
        val exposed: Boolean,
    )
}
