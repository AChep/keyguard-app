package com.artemchep.keyguard.feature.home.vault.search.engine

import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DTag

internal data class MetadataResolver(
    val values: Map<String, Set<String>>,
    val fuzzyValues: Set<String> = values.keys,
)

internal data class ExactFacetIndex(
    val account: Map<String, Set<Int>>,
    val folder: Map<String, Set<Int>>,
    val tag: Map<String, Set<Int>>,
    val organization: Map<String, Set<Int>>,
    val collection: Map<String, Set<Int>>,
    val type: Map<String, Set<Int>>,
    val favorite: Set<Int>,
    val reprompt: Set<Int>,
    val otp: Set<Int>,
    val attachments: Set<Int>,
    val passkeys: Set<Int>,
)

internal fun buildExactFacets(
    documents: Collection<VaultSearchDocument>,
    tokenizer: SearchTokenizer,
): ExactFacetIndex {
    val account = mutableMapOf<String, MutableSet<Int>>()
    val folder = mutableMapOf<String, MutableSet<Int>>()
    val tag = mutableMapOf<String, MutableSet<Int>>()
    val organization = mutableMapOf<String, MutableSet<Int>>()
    val collection = mutableMapOf<String, MutableSet<Int>>()
    val type = mutableMapOf<String, MutableSet<Int>>()
    val favorite = mutableSetOf<Int>()
    val reprompt = mutableSetOf<Int>()
    val otp = mutableSetOf<Int>()
    val attachments = mutableSetOf<Int>()
    val passkeys = mutableSetOf<Int>()

    documents.forEach { document ->
        val source = document.source
        account.getOrPut(source.accountId) { mutableSetOf() } += document.docId
        source.folderId?.let { folderId ->
            folder.getOrPut(folderId) { mutableSetOf() } += document.docId
        }
        source.tags.forEach { tagName ->
            val normalized =
                tokenizer.normalize(
                    value = tagName,
                    profile = SearchTokenizerProfile.TEXT,
                )
            if (normalized.isNotBlank()) {
                tag.getOrPut(normalized) { mutableSetOf() } += document.docId
            }
        }
        source.organizationId?.let { organizationId ->
            organization.getOrPut(organizationId) { mutableSetOf() } += document.docId
        }
        source.collectionIds.forEach { collectionId ->
            collection.getOrPut(collectionId) { mutableSetOf() } += document.docId
        }
        type.getOrPut(typeKey(source.type)) { mutableSetOf() } += document.docId
        if (source.favorite) {
            favorite += document.docId
        }
        if (source.reprompt) {
            reprompt += document.docId
        }
        if (source.login?.totp != null) {
            otp += document.docId
        }
        if (source.attachments.isNotEmpty()) {
            attachments += document.docId
        }
        if (source.login?.fido2Credentials?.isNotEmpty() == true) {
            passkeys += document.docId
        }
    }

    return ExactFacetIndex(
        account = account,
        folder = folder,
        tag = tag,
        organization = organization,
        collection = collection,
        type = type,
        favorite = favorite,
        reprompt = reprompt,
        otp = otp,
        attachments = attachments,
        passkeys = passkeys,
    )
}

internal fun buildAccountResolver(
    accounts: List<DAccount>,
    tokenizer: SearchTokenizer,
): MetadataResolver {
    val map = mutableMapOf<String, MutableSet<String>>()
    val fuzzyValues = mutableSetOf<String>()
    accounts.forEach { account ->
        listOfNotNull(
            account.accountId(),
            account.host,
            account.username,
            account.username?.let { "$it@${account.host}" },
        ).forEachIndexed { index, rawValue ->
            val normalized =
                tokenizer.normalize(
                    value = rawValue,
                    profile = SearchTokenizerProfile.IDENTIFIER,
                )
            if (normalized.isBlank()) {
                return@forEachIndexed
            }
            map.getOrPut(normalized) { mutableSetOf() } += account.accountId()
            if (index > 0) {
                fuzzyValues += normalized
            }
        }
    }
    return MetadataResolver(
        values = map,
        fuzzyValues = fuzzyValues,
    )
}

internal fun buildTagResolver(
    values: List<DTag>,
    tokenizer: SearchTokenizer,
): MetadataResolver {
    val map = mutableMapOf<String, MutableSet<String>>()
    values.forEach { tag ->
        val normalized =
            tokenizer.normalize(
                value = tag.name,
                profile = SearchTokenizerProfile.TEXT,
            )
        if (normalized.isBlank()) {
            return@forEach
        }
        map.getOrPut(normalized) { mutableSetOf() } += normalized
    }
    return MetadataResolver(
        values = map,
        fuzzyValues = map.keys,
    )
}

internal fun <T> buildNamedResolver(
    values: List<T>,
    tokenizer: SearchTokenizer,
    selector: (T) -> List<String>,
): MetadataResolver {
    val map = mutableMapOf<String, MutableSet<String>>()
    val fuzzyValues = mutableSetOf<String>()
    values.forEach { value ->
        val rawValues = selector(value)
        val id = rawValues.first()
        rawValues.forEachIndexed { index, rawValue ->
            val normalized =
                tokenizer.normalize(
                    value = rawValue,
                    profile =
                        if (index == 0) {
                            SearchTokenizerProfile.IDENTIFIER
                        } else {
                            SearchTokenizerProfile.TEXT
                        },
                )
            if (normalized.isBlank()) {
                return@forEachIndexed
            }
            map.getOrPut(normalized) { mutableSetOf() } += id
            if (index > 0) {
                fuzzyValues += normalized
            }
        }
    }
    return MetadataResolver(
        values = map,
        fuzzyValues = fuzzyValues,
    )
}

internal fun resolveMetadataIds(
    resolver: MetadataResolver,
    value: String,
): Set<String> {
    val exact = resolver.values[value].orEmpty()
    if (exact.isNotEmpty()) {
        return exact
    }

    return resolver.fuzzyValues
        .asSequence()
        .filter { normalizedValue -> normalizedValue.contains(value) }
        .flatMap { normalizedValue ->
            resolver.values[normalizedValue]
                ?.asSequence()
                ?: emptySequence()
        }.toSet()
        .ifEmpty { setOf(value) }
}
