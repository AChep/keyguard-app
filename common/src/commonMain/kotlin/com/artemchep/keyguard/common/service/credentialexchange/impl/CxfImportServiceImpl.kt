package com.artemchep.keyguard.common.service.credentialexchange.impl

import com.artemchep.keyguard.common.io.runCatchingUntrustedInput
import com.artemchep.keyguard.common.service.credentialexchange.CxfImportError
import com.artemchep.keyguard.common.service.credentialexchange.CxfImportPlan
import com.artemchep.keyguard.common.service.credentialexchange.CxfImportResult
import com.artemchep.keyguard.common.service.credentialexchange.CxfImportService
import com.artemchep.keyguard.common.service.credentialexchange.CxfImportSkipReason
import com.artemchep.keyguard.common.service.credentialexchange.CxfImportSkips
import com.artemchep.keyguard.common.service.credentialexchange.cxfImportSkips
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfAndroidAppId
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCollection
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredential
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialScope
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfEditableField
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfItem
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfLinkedItem
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfVersion
import com.artemchep.keyguard.common.service.crypto.PasskeyCrypto
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.crypto.NativePasskeyCrypto
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * The importer's document boundary.
 *
 * Every [runCatchingUntrustedInput] guard in this file sits inside [parse]'s
 * walk over a file handed over by another application, and none is reachable
 * from anywhere else. That is why the detekt allow-list for that helper names
 * this whole file: the file is the boundary.
 */
class CxfImportServiceImpl internal constructor(
    private val mapper: CxfImportSecretMapper,
) : CxfImportService {
    companion object {
        /**
         * Documents declaring major version `0` are accepted as CXF 1.0: some
         * providers historically emitted `0.0` for payloads that already follow
         * the 1.0 shape. Within major `1` any minor is accepted — CXF minors
         * are additive and unknown members are ignored anyway.
         */
        private const val VERSION_MAJOR_LEGACY = 0
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        passkeyCrypto = directDI.instance(),
        sshKeyImportService = directDI.instance(),
    )

    constructor(
        passkeyCrypto: PasskeyCrypto,
        sshKeyImportService: SshKeyImportService,
    ) : this(
        mapper = CxfImportSecretMapper(
            passkeyCrypto = passkeyCrypto,
            sshKeyImportService = sshKeyImportService,
        ),
    )

    constructor(
        sshKeyImportService: SshKeyImportService,
    ) : this(
        passkeyCrypto = NativePasskeyCrypto,
        sshKeyImportService = sshKeyImportService,
    )

    /**
     * A dedicated [Json] instance for the CXF wire format, matching the export
     * impl's pinned instance with one addition: unknown members are ignored,
     * which absorbs the spec's `extensions` members and any additive future
     * fields.
     */
    private val json = Json {
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    override fun parse(
        payload: String,
        now: Instant,
    ): CxfImportResult {
        val root = runCatchingUntrustedInput { json.parseToJsonElement(payload) }
            .getOrNull() as? JsonObject
            ?: return CxfImportResult.Failure(CxfImportError.Parse)
        val error = documentError(root)
        return if (error != null) {
            CxfImportResult.Failure(error)
        } else {
            // Planning walks attacker-supplied structure and reaches out to the
            // native crypto seam, neither of which is guaranteed total. The
            // contract here is that only a malformed document fails the parse,
            // so anything unexpected becomes exactly that — including a blown
            // stack or a blown heap, which at this boundary are properties of
            // the document, not of the process. See `runCatchingUntrustedInput`.
            runCatchingUntrustedInput { buildPlan(root, now) }
                .fold(
                    onSuccess = CxfImportResult::Success,
                    onFailure = { CxfImportResult.Failure(CxfImportError.Parse) },
                )
        }
    }

    private fun documentError(
        root: JsonObject,
    ): CxfImportError? {
        val version = root["version"]
            ?.let { element ->
                runCatchingUntrustedInput { json.decodeFromJsonElement<CxfVersion>(element) }.getOrNull()
            }
        val versionSupported = version != null &&
            (
                version.major == CxfVersion.CURRENT_MAJOR ||
                    (version.major == VERSION_MAJOR_LEGACY && version.minor == 0)
                )
        return when {
            version == null || root["accounts"] !is JsonArray -> CxfImportError.Parse
            versionSupported -> null
            else -> CxfImportError.UnsupportedVersion(
                major = version.major,
                minor = version.minor,
            )
        }
    }

    private fun buildPlan(
        root: JsonObject,
        now: Instant,
    ): CxfImportPlan {
        val folders = mutableListOf<CxfImportPlan.Folder>()
        val items = mutableListOf<CxfImportPlan.Item>()
        var skips = cxfImportSkips()
        val accountElements = (root["accounts"] as? JsonArray).orEmpty()
        val accounts = accountElements.mapNotNull { it as? JsonObject }
        // An entry that is not an object takes a whole account's worth of items
        // with it, so it has to be counted — reporting "nothing skipped" while
        // silently dropping an account is the one thing the review screen must
        // never do.
        skips = skips.plus(CxfImportSkipReason.Account, accountElements.size - accounts.size)
        accounts.forEachIndexed { accountIndex, account ->
            skips += parseAccount(
                account = account,
                keyPrefix = "account-$accountIndex",
                now = now,
                folders = folders,
                items = items,
            )
        }
        return CxfImportPlan(
            exporterRpId = root.stringMember("exporterRpId").orEmpty(),
            exporterDisplayName = root.stringMember("exporterDisplayName").orEmpty(),
            sourceAccountCount = accounts.size,
            folders = folders,
            items = items,
            skips = skips,
        )
    }

    private fun parseAccount(
        account: JsonObject,
        keyPrefix: String,
        now: Instant,
        folders: MutableList<CxfImportPlan.Folder>,
        items: MutableList<CxfImportPlan.Item>,
    ): CxfImportSkips {
        // CXF v1.0 §2.1.2 makes `collections` a required array that is present
        // even when empty, so `[]` is the only conforming way to say "no
        // folders". Absent, null and non-array are one violation with one
        // policy — the same shape as the `accounts` gate one level up.
        val decodedCollections = decodeCollections(
            element = account["collections"],
            missingIsALoss = true,
        )
        // Collections are non-critical adornment: an unreadable one degrades to
        // a folder-less import instead of failing it, and costs only its own
        // node, counted as `CxfImportSkipReason.Collection`.
        val folderPlan = buildImportFolderPlan(
            collections = decodedCollections.collections,
            accountId = account.stringMember("id"),
            keyPrefix = keyPrefix,
        )
        folders += folderPlan.folders
        val skips = cxfImportSkips(
            CxfImportSkipReason.Collection to decodedCollections.skippedCount,
        )
        // Absent, null, or any non-array value: the account decoded, yet an
        // unknown number of its items is unreachable. Count the account as
        // unread rather than importing it as if it were empty — §2.1.2 makes
        // `[]` the only conforming spelling of an empty account, so an omitted
        // `items` is a broken producer, not an empty one. The folders above are
        // kept: they read fine, and their links may name items of another account.
        val itemElements = account["items"] as? JsonArray
            ?: return skips + CxfImportSkipReason.Account
        return skips + parseItems(
            itemElements = itemElements,
            folderPlan = folderPlan,
            now = now,
            items = items,
        )
    }

    private fun parseItems(
        itemElements: JsonArray,
        folderPlan: CxfImportFolderPlan,
        now: Instant,
        items: MutableList<CxfImportPlan.Item>,
    ): CxfImportSkips {
        var skips = cxfImportSkips()
        itemElements.forEach { element ->
            val decoded = (element as? JsonObject)?.let(::decodeItem)
            if (decoded == null) {
                skips += CxfImportSkipReason.Item
                return@forEach
            }
            val result = mapper.mapItem(
                item = decoded.item,
                now = now,
            )
            // An item can disappear for two very different reasons: a credential
            // it held was already counted — by the decoder, or by the mapper — or
            // nothing in it maps to a vault item at all. Only the second case is
            // unaccounted for; counting both would report one loss twice in the
            // review screen's single "N skipped" total.
            //
            // A duplicate is the one reason that explains nothing: it was
            // discarded precisely *because* a sibling of the same kind was kept,
            // so letting it suppress the count would mean adding a second copy
            // of a credential silences the warning a single copy raises.
            val explainedByCredential = decoded.skippedCredentialCount > 0 ||
                result.skips.totalCount -
                result.skips[CxfImportSkipReason.DuplicateCredential] > 0
            // Built as this item's own tally and titled before being folded in.
            // `titled` attributes every reason it can see, so calling it on the
            // running accumulator would relabel every earlier item as this one.
            // `result.skips` arrives already attributed by the mapper.
            var itemSkips = cxfImportSkips(
                CxfImportSkipReason.UnknownCredential to decoded.skippedCredentialCount,
            ).titled(decoded.item.title) + result.skips
            if (result.requests.isEmpty() && !explainedByCredential) {
                itemSkips += cxfImportSkips(CxfImportSkipReason.Item to 1)
                    .titled(decoded.item.title)
            }
            skips += itemSkips
            val folderKey = folderPlan.folderKeyByItemId[decoded.item.id]
            result.requests.forEach { request ->
                items += CxfImportPlan.Item(
                    request = request,
                    folderKey = folderKey,
                )
            }
        }
        return skips
    }

    private data class DecodedCollections(
        val collections: List<CxfCollection>,
        val skippedCount: Int,
    ) {
        companion object {
            val EMPTY = DecodedCollections(collections = emptyList(), skippedCount = 0)

            /**
             * A member that could not be read at all. One count for unknowably
             * many — the same admission the account counter makes.
             */
            val UNREADABLE = DecodedCollections(collections = emptyList(), skippedCount = 1)
        }
    }

    /**
     * Decodes a `collections` member element by element, so one malformed node
     * costs only itself: decoding the array in one
     * `decodeFromJsonElement<List<CxfCollection>>` lets one bad entry — or one
     * bad great-grandchild, since [CxfCollection.subCollections] is decoded by
     * the same call — discard every folder of the account.
     *
     * [missingIsALoss] separates the two members this walks: an account's
     * `collections` is required and MUST be present even when empty (§2.1.2),
     * so absent is a loss there; a node's `subCollections` is optional and MUST
     * NOT be present when empty, so absent is how "no children" is spelled.
     *
     * Deliberately **unbounded**: the placement walk in [buildImportFolderPlan]
     * owns the depth policy, and truncating here would hide subtrees from it.
     * That is safe because this recursion is strictly shallower per nesting
     * level than the JSON parse that already ran on the same document, so any
     * tree `parseToJsonElement` managed to build is a tree this can walk.
     *
     * Measured, because it is the kind of claim that reads like a guess: on the
     * desktop JVM a `{"subCollections":[…]}` chain parses and plans identically
     * up to depth 2200 and both stages fail together at 2600, i.e.
     * `parseToJsonElement` is always the binding constraint and this walk never
     * is. The failure is a `StackOverflowError` inside the reader, absorbed by
     * [parse]'s guard as an ordinary malformed payload — so the ceiling costs
     * the document, never the process.
     */
    private fun decodeCollections(
        element: JsonElement?,
        missingIsALoss: Boolean,
    ): DecodedCollections {
        val array = element as? JsonArray
        if (array == null) {
            val missing = element == null || element is JsonNull
            return if (missing && !missingIsALoss) {
                DecodedCollections.EMPTY
            } else {
                DecodedCollections.UNREADABLE
            }
        }
        val collections = mutableListOf<CxfCollection>()
        var skippedCount = 0
        array.forEach { child ->
            val decoded = decodeCollection(child)
            collections += decoded.collections
            skippedCount += decoded.skippedCount
        }
        return DecodedCollections(
            collections = collections,
            skippedCount = skippedCount,
        )
    }

    /**
     * Decodes one collection node. Mirrors [decodeItem]: the node is decoded
     * with both of its multi-sibling containers emptied — the nested
     * [CxfCollection] serializer would otherwise fail the node on the first bad
     * descendant, and the generated [CxfLinkedItem] serializer on the first
     * unusable link — and each is read on its own and spliced back in.
     *
     * `items` is required with no default because §2.1.2 puts it on the wire
     * even when empty, which made decoding it inside the shell expensive: a
     * producer that omits the empty array — a common way to spell "no items" —
     * lost the account's **entire folder hierarchy**, one `Collection` skip per
     * node, with every item imported folder-less. Reading it here costs an
     * unusable link only its own link; that is an uncounted organization loss,
     * registered in `CxfImportSilentDropTest`.
     *
     * A node that cannot be read is replaced by its children rather than taking
     * them with it, so the tree loses one level and not a subtree. Only that
     * node's own title and its own item links are lost; the items it linked
     * still import, folder-less.
     */
    private fun decodeCollection(
        element: JsonElement,
    ): DecodedCollections {
        val obj = element as? JsonObject
            ?: return DecodedCollections.UNREADABLE
        val children = decodeCollections(
            element = obj["subCollections"],
            missingIsALoss = false,
        )
        val links = (obj["items"] as? JsonArray)
            .orEmpty()
            .mapNotNull { link -> link.toLinkedItemOrNull() }
        val shellObject = JsonObject(
            obj.toMutableMap().apply {
                put("subCollections", JsonArray(emptyList()))
                put("items", JsonArray(emptyList()))
            },
        )
        val shell = runCatchingUntrustedInput { json.decodeFromJsonElement<CxfCollection>(shellObject) }
            .getOrNull()
        return if (shell == null) {
            children.copy(skippedCount = children.skippedCount + 1)
        } else {
            DecodedCollections(
                collections = listOf(
                    shell.copy(
                        items = links,
                        subCollections = children.collections.takeIf { it.isNotEmpty() },
                    ),
                ),
                skippedCount = children.skippedCount,
            )
        }
    }

    private data class DecodedItem(
        val item: CxfItem,
        val skippedCredentialCount: Int,
    )

    /**
     * Decodes one item with per-credential leniency: the item shell is
     * decoded with an emptied `credentials` member (the sealed
     * [CxfCredential] serializer would fail the whole item on the first
     * unknown `type` discriminator), then each credential is decoded
     * individually and failures become counted skips.
     *
     * `scope` is lifted out of the shell for the same reason and spliced back
     * by `decodeScope`, which reads its two arrays on their own.
     */
    private fun decodeItem(
        element: JsonObject,
    ): DecodedItem? {
        val shellObject = JsonObject(
            element.toMutableMap().apply {
                put("credentials", JsonArray(emptyList()))
                remove("scope")
            },
        )
        val shell = decodeItemShell(shellObject)
            ?: return null
        var skippedCredentialCount = 0
        val credentialElements = (element["credentials"] as? JsonArray).orEmpty()
        val credentials = credentialElements.mapNotNull { credentialElement ->
            json.decodeCredentialLeniently(credentialElement)
                .also { if (it == null) skippedCredentialCount++ }
        }
        return DecodedItem(
            item = shell.copy(
                credentials = credentials,
                scope = json.decodeScope(element["scope"]),
            ),
            skippedCredentialCount = skippedCredentialCount,
        )
    }

    /**
     * Decodes the item shell, retrying element-wise when a member the item
     * does not need refuses to decode.
     *
     * `id` and `title` are the only members an item cannot do without;
     * everything in [ITEM_ADORNMENT_MEMBERS] is optional decoration. Because
     * the whole shell is one `decodeFromJsonElement` call, a producer writing
     * an ISO-8601 string or a fractional epoch into `modifiedAt` — both common
     * conventions — would otherwise cost the item every password and every
     * passkey it held.
     *
     * So the retry drops every adornment, then re-admits them one at a time
     * and keeps the ones that decode. A dropped member is a field-level
     * fidelity loss inside a surviving item, which `CxfImportSkipReason`'s
     * contract deliberately does not count; the register of those silences is
     * `CxfImportSilentDropTest`.
     */
    private fun decodeItemShell(
        shellObject: JsonObject,
    ): CxfItem? = decodeItemShellOrNull(shellObject)
        ?: decodeItemShellWithoutBadAdornments(shellObject)

    private fun decodeItemShellOrNull(
        shellObject: JsonObject,
    ): CxfItem? = runCatchingUntrustedInput { json.decodeFromJsonElement<CxfItem>(shellObject) }
        .getOrNull()

    private fun decodeItemShellWithoutBadAdornments(
        shellObject: JsonObject,
    ): CxfItem? {
        var accepted = JsonObject(
            shellObject.toMutableMap().apply {
                ITEM_ADORNMENT_MEMBERS.forEach { key -> remove(key) }
            },
        )
        var shell = decodeItemShellOrNull(accepted)
            ?: return null
        ITEM_ADORNMENT_MEMBERS.forEach { key ->
            val value = shellObject[key]
                ?: return@forEach
            val candidate = JsonObject(
                accepted.toMutableMap().apply { put(key, value) },
            )
            val decoded = decodeItemShellOrNull(candidate)
                ?: return@forEach
            accepted = candidate
            shell = decoded
        }
        return shell
    }
}

/**
 * The `@JsonClassDiscriminator` of [CxfCredential], and the one credential
 * subtype whose payload is a bag of independently-decodable members.
 */
private const val CREDENTIAL_DISCRIMINATOR = "type"
private const val CREDENTIAL_TYPE_CUSTOM_FIELDS = "custom-fields"
private const val CUSTOM_FIELDS_MEMBER = "fields"

/**
 * How many members [decodeCredentialDroppingOneMember] probes.
 *
 * The widest credential CXF v1.0 §3.3 defines is `passport`, with twelve
 * members beside the discriminator, so this admits every conforming credential
 * with room to spare for `extensions`. It exists because the probe is one
 * decode *per member*: an object built with a hundred thousand members would
 * otherwise turn the retry into a quadratic walk over attacker-chosen input.
 */
private const val MAX_CREDENTIAL_MEMBER_PROBES = 16

/**
 * The members of [CxfItem] that carry no credential and no identity: an item
 * without them is still the same item. Ordered as the class declares them,
 * which is the order the retry re-admits them in.
 *
 * `scope` is absent on purpose: `decodeItem` lifts it out of the shell and
 * [decodeScope] reads it, so the all-or-nothing retry never sees it.
 */
private val ITEM_ADORNMENT_MEMBERS = listOf(
    "creationAt",
    "modifiedAt",
    "subtitle",
    "favorite",
    "tags",
)

/**
 * Decodes one credential, retrying member-wise when the whole-credential decode
 * fails.
 *
 * [CxfEditableField] declares `fieldType` and `value` without defaults, and a
 * credential is decoded by one generated serializer call, so one member
 * carrying a JSON number or boolean `value` — or missing `fieldType` entirely,
 * or written as the bare string some producers use as shorthand, all ordinary
 * producer bugs — used to cost the item the *whole* credential: every custom
 * field of a bag, the twelve optional members of a `passport`, the nine of a
 * `person-name`, or the password sitting beside a shorthand `username`. CXF
 * v1.0 §3.4.2 asks for the opposite granularity: an unusable field structure is
 * treated as though the member holding it were not provided.
 *
 * So the retry [prunes][prunedCredentialOrNull] the members that cannot be read
 * as a field at all and, when that is not enough,
 * [probes][decodeCredentialDroppingOneMember] the rest one at a time.
 * Requiredness needs no table here: the serializer refuses a credential that
 * lost a required member, so only an optional one is ever dropped successfully
 * and nothing is fabricated to take its place.
 *
 * No multi-sibling container in this file is decoded whole any more:
 * `decodeItem` reads `credentials`, `decodeCollections` reads the collection
 * tree, `decodeCollection` reads a node's `items`, [decodeScope] reads its two
 * arrays, and a bag's `fields` are read here. A dropped member is a field-level
 * fidelity loss inside a surviving credential, which [CxfImportSkipReason]'s
 * contract deliberately does not count — the register of those silences is
 * `CxfImportSilentDropTest`.
 */
private fun Json.decodeCredentialLeniently(
    element: JsonElement,
): CxfCredential? = decodeCredentialOrNull(element)
    ?: (element as? JsonObject)
        ?.let { obj -> prunedCredentialOrNull(obj) }
        ?.let { pruned ->
            decodeCredentialOrNull(pruned)
                ?: decodeCredentialDroppingOneMember(pruned)
        }

private fun Json.decodeCredentialOrNull(
    element: JsonElement,
): CxfCredential? = runCatchingUntrustedInput {
    decodeFromJsonElement<CxfCredential>(element)
}.getOrNull()

/**
 * The credential with every member that cannot be read as a [CxfEditableField]
 * *structure* removed, or `null` when the retry must not be attempted at all.
 *
 * Only JSON objects are pruned. An object standing where an `EditableField`
 * belongs carries nothing usable whatever it holds, which is exactly the member
 * §3.4.2 says to treat as absent; a scalar member, by contrast, may be one the
 * credential legitimately declares as a plain string — `ssh-key`'s `keyType`,
 * `totp`'s `secret` — so those are left to
 * [decodeCredentialDroppingOneMember]. The `type` discriminator is a string, so
 * it is never a candidate.
 *
 * A `custom-fields` bag is additionally filtered element-wise, and answers
 * `null` when nothing in it survives: an empty bag decodes, so admitting one
 * would turn a real loss into a silent one instead of a counted credential skip.
 */
private fun Json.prunedCredentialOrNull(
    obj: JsonObject,
): JsonObject? {
    val withoutBrokenFields = JsonObject(
        obj.filterNot { (_, value) -> value is JsonObject && !isEditableField(value) },
    )
    val isFieldBag = obj[CREDENTIAL_DISCRIMINATOR].jsonStringOrNull() == CREDENTIAL_TYPE_CUSTOM_FIELDS
    return if (isFieldBag) {
        withoutBrokenFields.withDecodableFieldsOnly { field -> isEditableField(field) }
    } else {
        withoutBrokenFields
    }
}

/**
 * Whether the element is a readable [CxfEditableField].
 */
private fun Json.isEditableField(
    element: JsonElement,
): Boolean = runCatchingUntrustedInput {
    decodeFromJsonElement<CxfEditableField>(element)
}.isSuccess

/**
 * The last resort: the credential decoded once per member with exactly that one
 * member dropped, keeping the first result that decodes.
 *
 * This is what recovers the members the wire format lets a producer spell as a
 * bare scalar where a structure belongs — a shorthand `username`, a numeric
 * `label` or `id` on a field bag — without a per-type table of what is
 * optional. Dropping a required member fails the decode, so the walk can only
 * ever settle on an optional one.
 *
 * Bounded by [MAX_CREDENTIAL_MEMBER_PROBES], and lazily so: the sequence stops
 * at the first member that works.
 */
private fun Json.decodeCredentialDroppingOneMember(
    obj: JsonObject,
): CxfCredential? = obj.keys
    .asSequence()
    .filterNot { key -> key == CREDENTIAL_DISCRIMINATOR }
    .take(MAX_CREDENTIAL_MEMBER_PROBES)
    .firstNotNullOfOrNull { key -> decodeCredentialOrNull(JsonObject(obj - key)) }

/**
 * The receiver with the elements of a `custom-fields` bag that will not decode
 * removed, or `null` when none of them would survive.
 */
private fun JsonObject.withDecodableFieldsOnly(
    decodes: (JsonElement) -> Boolean,
): JsonObject? {
    // A bag whose `fields` member is not an array at all has nothing to filter;
    // it is the enclosing retry's business, not this one's.
    val fields = this[CUSTOM_FIELDS_MEMBER] as? JsonArray
        ?: return this
    return fields
        .filter(decodes)
        // Nothing survived, so the bag carries no data and must stay a counted
        // credential skip rather than become an empty credential.
        .takeIf { it.isNotEmpty() }
        ?.let { fieldsToKeep ->
            JsonObject(
                toMutableMap().apply {
                    put(CUSTOM_FIELDS_MEMBER, JsonArray(fieldsToKeep))
                },
            )
        }
}

/**
 * Reads one `items` entry of a collection by hand, the way [decodeScope] reads
 * a scope's `urls`: an entry that is not an object, or whose required `item` is
 * not a JSON string, is dropped rather than coerced into an id that would match
 * no item.
 */
private fun JsonElement.toLinkedItemOrNull(): CxfLinkedItem? = (this as? JsonObject)
    ?.let { obj ->
        obj["item"]
            .jsonStringOrNull()
            ?.let { item ->
                CxfLinkedItem(
                    item = item,
                    account = obj["account"].jsonStringOrNull(),
                )
            }
    }

/**
 * Decodes an item's `scope` member, reading its two arrays independently.
 *
 * [CxfCredentialScope] declares `urls` and `androidApps` without defaults on
 * purpose: CXF v1.0 §2.1.2 requires both arrays on the wire even when empty,
 * and a default would let the exporter's `encodeDefaults = false` writer omit
 * the empty one. Decoding an incoming scope through that same serializer is
 * therefore all-or-nothing, so a producer that omits the array it has nothing
 * to put in would cost every one of its logins the whole url list — autofill
 * matches on uri, so those items would land in the vault permanently unmatched
 * while the review screen reported a clean import.
 *
 * Hence the member is read here rather than through the model, element by
 * element like the collection walk: one unreadable url or app costs only
 * itself. Those are field-level fidelity losses inside a surviving item, which
 * `CxfImportSkipReason` deliberately does not count; they are pinned in
 * `CxfImportSilentDropTest`.
 */
private fun Json.decodeScope(
    element: JsonElement?,
): CxfCredentialScope? {
    val obj = element as? JsonObject
        ?: return null
    val urls = (obj["urls"] as? JsonArray).orEmpty()
        .mapNotNull { url -> url.jsonStringOrNull() }
    val androidApps = (obj["androidApps"] as? JsonArray).orEmpty()
        .mapNotNull { app ->
            runCatchingUntrustedInput { decodeFromJsonElement<CxfAndroidAppId>(app) }.getOrNull()
        }
    return CxfCredentialScope(
        urls = urls,
        androidApps = androidApps,
    )
}

/**
 * The value of a member that really is a JSON string. Unlike [stringMember] a
 * number or a boolean is not coerced into text, which is what the two places
 * reading an *identifier* out of an array need: a fabricated id looks like a
 * link and matches nothing.
 */
private fun JsonElement?.jsonStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.stringMember(
    key: String,
): String? = (this[key] as? JsonPrimitive)?.contentOrNull
