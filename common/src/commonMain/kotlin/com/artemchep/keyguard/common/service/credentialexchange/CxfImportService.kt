package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.create.CreateRequest
import kotlin.time.Instant

/**
 * Parses unencrypted FIDO Credential Exchange Format (CXF) v1.0 documents
 * received from another credential provider and plans the vault items to
 * create from them.
 *
 * The service is transport-agnostic and free of any platform dependencies —
 * the mirror image of [CxfExportService].
 */
interface CxfImportService {
    /**
     * Parses a CXF document [payload] into an import plan. The parsing is
     * lenient: unknown JSON members are ignored and unknown credential kinds
     * are counted as skipped. Only a malformed payload or an unsupported
     * document version fails the parse.
     *
     * [now] stamps the planned create requests; it also becomes the creation
     * date of passkeys whose item carries no creation timestamp.
     */
    fun parse(
        payload: String,
        now: Instant,
    ): CxfImportResult
}

sealed interface CxfImportResult {
    data class Success(
        val plan: CxfImportPlan,
    ) : CxfImportResult

    data class Failure(
        val error: CxfImportError,
    ) : CxfImportResult
}

/**
 * A parse-level failure. Per-credential and per-item problems never surface
 * here — they become counted skips on the [plan][CxfImportPlan.skips].
 */
sealed interface CxfImportError {
    /**
     * The payload is not a JSON object or misses required structure.
     */
    data object Parse : CxfImportError

    /**
     * The document declares a CXF version this implementation does not
     * understand.
     */
    data class UnsupportedVersion(
        val major: Int,
        val minor: Int,
    ) : CxfImportError
}

/**
 * Everything needed to review and then commit an import: the folders to
 * create, the items to create (with their folder assignment), and the counts
 * of whatever could not be represented.
 *
 * The plan is target-account agnostic — every [CreateRequest] has a `null`
 * ownership, which the committing flow fills in together with the resolved
 * folder ids.
 */
data class CxfImportPlan(
    /**
     * The relying-party identifier the exporting application declared for
     * itself, e.g. its package name. Display-only.
     */
    val exporterRpId: String,
    /**
     * A human-readable name of the exporting application. Display-only.
     */
    val exporterDisplayName: String,
    /**
     * The number of accounts the source document contained. Keyguard merges
     * every source account into the single target account; the review screen
     * mentions the source count when it is greater than one.
     */
    val sourceAccountCount: Int,
    /**
     * The folders to create, in an order that guarantees every
     * [parent][Folder.parentKey] appears before its children.
     */
    val folders: List<Folder>,
    val items: List<Item>,
    val skips: CxfImportSkips,
) {
    /**
     * A folder to create, keyed by a plan-local identifier — real folder ids
     * exist only after the commit step created them.
     */
    data class Folder(
        val key: String,
        /**
         * The plan-local key of the parent folder, or `null` for a root
         * folder.
         */
        val parentKey: String?,
        val title: String,
    )

    /**
     * A vault item to create. [request] carries no ownership; [folderKey]
     * references the planned folder the item belongs to, if any.
     */
    data class Item(
        val request: CreateRequest,
        val folderKey: String?,
    )
}
