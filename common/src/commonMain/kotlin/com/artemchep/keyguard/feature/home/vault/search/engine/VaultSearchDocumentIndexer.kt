package com.artemchep.keyguard.feature.home.vault.search.engine

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultTextField

internal class VaultSearchDocumentIndexer(
    private val tokenizer: SearchTokenizer,
) {
    fun build(
        docId: Int,
        secret: DSecret,
    ): DocumentBuildResult {
        val hotFields = mutableMapOf<VaultTextField, VaultSearchFieldData>()
        val coldFields = mutableMapOf<VaultTextField, VaultSearchFieldData>()
        val hotPostings = mutableMapOf<VaultTextField, Map<String, Int>>()
        val fieldDocFrequencies = mutableMapOf<VaultTextField, Set<String>>()
        val fieldLengths = mutableMapOf<VaultTextField, Int>()

        hotFieldValues(secret).forEach { (field, values) ->
            val fieldData =
                indexFieldValues(
                    field = field,
                    values = values,
                    cachePerValueTerms = false,
                )
            hotFields[field] = fieldData
            hotPostings[field] = fieldData.termFrequencies
            fieldDocFrequencies[field] = fieldData.termFrequencies.keys
            fieldLengths[field] = fieldData.totalTerms
        }

        coldFieldValues(secret).forEach { (field, values) ->
            val fieldData =
                indexFieldValues(
                    field = field,
                    values = values,
                    cachePerValueTerms = true,
                )
            if (fieldData.values.isNotEmpty()) {
                coldFields[field] = fieldData
                fieldDocFrequencies[field] = fieldData.termFrequencies.keys
                fieldLengths[field] = fieldData.totalTerms
            }
        }

        return DocumentBuildResult(
            document =
                VaultSearchDocument(
                    docId = docId,
                    sourceId = secret.id,
                    source = secret,
                    hotFields = hotFields,
                    coldFields = coldFields,
                ),
            hotPostings = hotPostings,
            fieldDocFrequencies = fieldDocFrequencies,
            fieldLengths = fieldLengths,
        )
    }

    private fun indexFieldValues(
        field: VaultTextField,
        values: List<String>,
        cachePerValueTerms: Boolean,
    ): VaultSearchFieldData {
        val termFrequencies = mutableMapOf<String, Int>()
        val exactTermFrequencies = mutableMapOf<String, Int>()
        var totalTerms = 0
        val indexedValues =
            values.mapNotNull { raw ->
                val tokenization =
                    tokenizer.tokenize(
                        value = raw,
                        profile = field.profile(),
                    )
                if (tokenization.terms.isEmpty()) {
                    return@mapNotNull null
                }
                tokenization.terms.forEach { term ->
                    termFrequencies[term] = termFrequencies.getOrElse(term) { 0 } + 1
                    totalTerms += 1
                }
                tokenization.exactTerms.forEach { term ->
                    exactTermFrequencies[term] = exactTermFrequencies.getOrElse(term) { 0 } + 1
                }
                buildIndexedFieldValue(
                    raw = raw,
                    tokenization = tokenization,
                    cachePerValueTerms = cachePerValueTerms,
                )
            }
        return VaultSearchFieldData(
            values = indexedValues,
            termFrequencies = termFrequencies,
            exactTermFrequencies = exactTermFrequencies,
            totalTerms = totalTerms,
        )
    }
}

private fun buildIndexedFieldValue(
    raw: String,
    tokenization: SearchTokenization,
    cachePerValueTerms: Boolean,
): IndexedFieldValue =
    IndexedFieldValue(
        raw = raw,
        normalized = tokenization.normalizedText,
        exactNormalized = tokenization.exactNormalizedText,
        normalizedTerms = tokenization.terms.distinct().takeIf { cachePerValueTerms },
        exactNormalizedTerms = tokenization.exactTerms.distinct().takeIf { cachePerValueTerms },
    )
