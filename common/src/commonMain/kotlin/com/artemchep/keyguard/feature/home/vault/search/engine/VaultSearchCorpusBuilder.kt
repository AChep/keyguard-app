package com.artemchep.keyguard.feature.home.vault.search.engine

import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultTextField

internal fun buildSearchCorpus(
    buildResults: Collection<DocumentBuildResult>,
    tokenizer: SearchTokenizer,
): SearchCorpus {
    val capacity = collectionCapacity(buildResults.size)
    val documents = LinkedHashMap<Int, VaultSearchDocument>(capacity)
    val docIdsBySourceId = LinkedHashMap<String, Int>(capacity)
    val postings =
        mutableMapOf<VaultTextField, MutableMap<String, MutableList<SearchPosting>>>()
    val fieldLengthTotals = mutableMapOf<VaultTextField, Long>()
    val fieldDocumentCounts = mutableMapOf<VaultTextField, Int>()
    val fieldDocFrequencies =
        mutableMapOf<VaultTextField, MutableMap<String, Int>>()
    buildResults.forEach { result ->
        documents[result.document.docId] = result.document
        docIdsBySourceId[result.document.sourceId] = result.document.docId
        result.hotPostings.forEach { (field, terms) ->
            val fieldPostings = postings.getOrPut(field) { mutableMapOf() }
            terms.forEach { (term, frequency) ->
                fieldPostings
                    .getOrPut(term) { mutableListOf() }
                    .add(SearchPosting(result.document.docId, frequency))
            }
        }
        result.fieldLengths.forEach { (field, length) ->
            fieldLengthTotals[field] = fieldLengthTotals.getOrElse(field) { 0L } + length
            fieldDocumentCounts[field] = fieldDocumentCounts.getOrElse(field) { 0 } + 1
        }
        result.fieldDocFrequencies.forEach { (field, terms) ->
            val fieldFrequencies =
                fieldDocFrequencies.getOrPut(field) { mutableMapOf() }
            terms.forEach { term ->
                fieldFrequencies[term] = fieldFrequencies.getOrElse(term) { 0 } + 1
            }
        }
    }

    return SearchCorpus(
        documents = documents,
        docIdsBySourceId = docIdsBySourceId,
        postings = postings,
        fieldStats =
            fieldLengthTotals.mapValues { (field, totalLength) ->
                val documentCount = fieldDocumentCounts.getValue(field)
                SearchFieldStats(
                    averageLength = totalLength.toDouble() / documentCount,
                    documentFrequency = fieldDocFrequencies[field].orEmpty(),
                )
            },
        exactFacets =
            buildExactFacets(
                documents = documents.values,
                tokenizer = tokenizer,
            ),
    )
}

private fun collectionCapacity(size: Int): Int =
    if (size < 3) {
        size + 1
    } else {
        (size / 0.75f + 1.0f).toInt()
    }
