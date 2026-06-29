package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryValue
import com.artemchep.keyguard.core.store.bitwarden.CanonicalFieldRef
import com.artemchep.keyguard.core.store.bitwarden.CipherSourceData
import com.artemchep.keyguard.core.store.bitwarden.CipherSourceProviderIds
import com.artemchep.keyguard.core.store.bitwarden.SourceBinding
import com.artemchep.keyguard.core.store.bitwarden.SourceFieldRef
import com.artemchep.keyguard.core.store.bitwarden.SourceProjection
import com.artemchep.keyguard.core.store.bitwarden.SourceProjectionId
import com.artemchep.keyguard.core.store.bitwarden.SourceRepresentation
import com.artemchep.keyguard.core.store.bitwarden.SourceRole
import com.artemchep.keyguard.core.store.bitwarden.targets
import com.artemchep.keyguard.core.store.bitwarden.withoutCanonicalPaths

/**
 * Shared source-binding helpers for the KeePass codecs. Centralizes the
 * concealment derivation, field-order lookup, and the strip/append/prune envelope
 * that every sub-codec used to re-implement.
 */

/** True when [this] KeePass value is memory-protected (concealed). */
internal fun EntryValue.isConcealed(): Boolean = this is EntryValue.Encrypted

/** Positional index of [key] among the entry's fields, or null if absent. */
internal fun Entry.fieldOrder(key: String): Int? =
    fields.entries.indexOfFirst { it.key == key }.takeIf { it >= 0 }

/**
 * Resolves a source field's concealment, falling back to [default] when the field
 * did not record one (concealed == null). Single shared semantic for every codec.
 */
internal fun SourceFieldRef?.isConcealedByDefault(default: Boolean): Boolean =
    this?.concealed ?: default

/** Builds a decode-side [SourceFieldRef], capturing concealment + order from [remote]. */
internal fun decodedSourceFieldRef(
    remote: Entry,
    key: String,
    value: EntryValue,
    role: SourceRole? = null,
    representationId: SourceRepresentation? = null,
): SourceFieldRef = SourceFieldRef(
    key = key,
    role = role,
    representationId = representationId,
    concealed = value.isConcealed(),
    order = remote.fieldOrder(key),
)

/**
 * Rebuilds the KeePass [CipherSourceData] for one encode pass: keeps only KeePass
 * bindings, strips [stripPaths], appends [newBindings], and collapses to null when
 * empty. Centralizes the strip/append/prune envelope each codec repeated.
 */
internal fun CipherSourceData?.rebuildKeepass(
    stripPaths: Set<String>,
    newBindings: List<SourceBinding>,
): CipherSourceData? {
    val base = this
        ?.takeIf { it.providerId == CipherSourceProviderIds.KEEPASS }
        ?.withoutCanonicalPaths(stripPaths)
        ?: CipherSourceData(providerId = CipherSourceProviderIds.KEEPASS)
    return base
        .copy(bindings = base.bindings + newBindings)
        .takeUnless { it.bindings.isEmpty() }
}

/**
 * KeePass bindings whose [SourceBinding.canonicalFields] intersect [paths].
 * Provider-guarded: returns empty for non-KeePass (or null) source data. This is
 * the encode-side preamble every producer codec repeated by hand.
 */
internal fun CipherSourceData?.keepassBindingsFor(paths: Set<String>): List<SourceBinding> =
    this
        ?.takeIf { it.providerId == CipherSourceProviderIds.KEEPASS }
        ?.bindings
        .orEmpty()
        .filter { binding -> binding.canonicalFields.any { it.path in paths } }

/** True when every (key, value) in [selector] is present on some canonical field. */
internal fun SourceBinding.matchesSelector(selector: Map<String, String>): Boolean =
    selector.all { (key, value) -> canonicalFields.any { it.selector[key] == value } }

/** First binding that targets [path] and matches [selector] (empty selector matches any). */
internal fun List<SourceBinding>.bindingFor(
    path: String,
    selector: Map<String, String> = emptyMap(),
): SourceBinding? =
    firstOrNull { it.targets(path) && it.matchesSelector(selector) }

/** First source field of the first binding that targets [path]. */
internal fun List<SourceBinding>.sourceFieldFor(
    path: String,
    selector: Map<String, String> = emptyMap(),
): SourceFieldRef? =
    bindingFor(path, selector)?.sourceFields?.firstOrNull()

/**
 * Builds an encode-side [SourceFieldRef], the inverse of [decodedSourceFieldRef]:
 * reuses [existing]'s order/parameters when present and overwrites the rest.
 * Single shared idiom for every codec's binding regeneration.
 */
internal fun encodedSourceFieldRef(
    key: String,
    concealed: Boolean,
    role: SourceRole? = null,
    representationId: SourceRepresentation? = null,
    order: Int? = null,
    existing: SourceFieldRef? = null,
): SourceFieldRef =
    (existing ?: SourceFieldRef(key = key)).copy(
        key = key,
        role = role,
        representationId = representationId,
        concealed = concealed,
        order = order ?: existing?.order,
    )

/**
 * Assembles one [SourceBinding] from a ready list of [sourceFields]. Used by both
 * decode and encode and by single- and multi-field codecs (pass a one-element
 * [sourceFields] for the single-field case), so every codec produces the same shape.
 */
internal fun sourceBinding(
    canonicalPaths: List<String>,
    sourceFields: List<SourceFieldRef>,
    projectionId: SourceProjectionId?,
    selector: Map<String, String> = emptyMap(),
    parameters: Map<String, String> = emptyMap(),
    projectionParameters: Map<String, String> = emptyMap(),
): SourceBinding = SourceBinding(
    sourceFields = sourceFields,
    canonicalFields = canonicalPaths.map { path ->
        CanonicalFieldRef(path = path, selector = selector)
    },
    projection = SourceProjection(id = projectionId, parameters = projectionParameters),
    parameters = parameters,
)
