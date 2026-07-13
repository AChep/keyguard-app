package com.artemchep.keyguard.feature.home.vault.search.engine

import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultTextField

internal fun buildSearchCorpus(
    buildResults: Collection<DocumentBuildResult>,
    tokenizer: SearchTokenizer,
): SearchCorpus {
    val documents = buildResults.associate { it.document.docId to it.document }
    val docIdsBySourceId =
        buildResults.associate { it.document.sourceId to it.document.docId }
    val postings =
        mutableMapOf<VaultTextField, MutableMap<String, MutableList<SearchPosting>>>()
    val fieldLengths = mutableMapOf<VaultTextField, MutableList<Int>>()
    val fieldDocFrequencies =
        mutableMapOf<VaultTextField, MutableMap<String, Int>>()
    buildResults.forEach { result ->
        result.hotPostings.forEach { (field, terms) ->
            val fieldPostings = postings.getOrPut(field) { mutableMapOf() }
            terms.forEach { (term, frequency) ->
                fieldPostings
                    .getOrPut(term) { mutableListOf() }
                    .add(SearchPosting(result.document.docId, frequency))
            }
        }
        result.fieldLengths.forEach { (field, length) ->
            fieldLengths.getOrPut(field) { mutableListOf() } += length
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
        postings =
            postings.mapValues { entry ->
                entry.value.mapValues { it.value.toList() }
            },
        fieldStats =
            fieldLengths.mapValues { (field, lengths) ->
                SearchFieldStats(
                    averageLength = lengths.average().takeIf { !it.isNaN() } ?: 0.0,
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
