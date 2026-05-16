package com.artemchep.keyguard.feature.home.vault.search.engine

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultSearchQueryCompiler
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultTextField
import com.artemchep.keyguard.feature.home.vault.search.query.parser.VaultSearchParser
import kotlin.time.TimeSource

internal class DefaultVaultSearchIndexBuilder(
    private val tokenizer: SearchTokenizer,
    private val scorer: SearchScorer,
    private val executor: SearchExecutor,
    private val parser: VaultSearchParser,
    private val compiler: VaultSearchQueryCompiler,
    private val traceSink: VaultSearchTraceSink = NoOpVaultSearchTraceSink,
) : VaultSearchIndexBuilder {
    private var cachedCorpusState = CachedCorpusState()

    override suspend fun build(
        items: List<DSecret>,
        metadata: VaultSearchIndexMetadata,
        surface: String?,
    ): VaultSearchIndex {
        if (traceSink.isEnabled) {
            traceSink.index(
                IndexTraceEvent(
                    surface = surface,
                    phase = IndexTracePhase.Start,
                    itemCount = items.size,
                    accountCount = metadata.accounts.size,
                    folderCount = metadata.folders.size,
                    tagCount = metadata.tags.size,
                    collectionCount = metadata.collections.size,
                    organizationCount = metadata.organizations.size,
                ),
            )
        }
        val buildStart = TimeSource.Monotonic.markNow()
        var nextDocId = cachedCorpusState.nextDocId
        var rebuiltDocumentCount = 0
        var reusedDocumentCount = 0
        val nextDocumentsBySourceId = LinkedHashMap<String, CachedDocumentState>(items.size)
        items.forEach { secret ->
            val fingerprint = searchFingerprint(secret)
            val previous = cachedCorpusState.documentsBySourceId[secret.id]
            if (previous != null && previous.fingerprint == fingerprint) {
                nextDocumentsBySourceId[secret.id] = previous
                reusedDocumentCount += 1
            } else {
                val docId = previous?.buildResult?.document?.docId ?: nextDocId++
                nextDocumentsBySourceId[secret.id] =
                    CachedDocumentState(
                        fingerprint = fingerprint,
                        buildResult =
                            buildDocument(
                                docId = docId,
                                secret = secret,
                                tokenizer = tokenizer,
                            ),
                    )
                rebuiltDocumentCount += 1
            }
        }
        val removedDocumentCount =
            (
                cachedCorpusState.documentsBySourceId.keys -
                    nextDocumentsBySourceId.keys
            ).size
        cachedCorpusState =
            CachedCorpusState(
                nextDocId = nextDocId,
                documentsBySourceId = nextDocumentsBySourceId,
            )

        val buildResults =
            nextDocumentsBySourceId.values
                .map(CachedDocumentState::buildResult)

        val documents = buildResults.associate { it.document.docId to it.document }
        val docIdsBySourceId = buildResults.associate { it.document.sourceId to it.document.docId }

        val postings = mutableMapOf<VaultTextField, MutableMap<String, MutableList<SearchPosting>>>()
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
                val fieldFrequencies = fieldDocFrequencies.getOrPut(field) { mutableMapOf() }
                terms.forEach { term ->
                    fieldFrequencies[term] = fieldFrequencies.getOrElse(term) { 0 } + 1
                }
            }
        }

        val exactFacets =
            buildExactFacets(
                documents = documents.values,
                tokenizer = tokenizer,
            )
        val stats =
            fieldLengths.mapValues { (field, lengths) ->
                SearchFieldStats(
                    averageLength = lengths.average().takeIf { !it.isNaN() } ?: 0.0,
                    documentFrequency = fieldDocFrequencies[field].orEmpty(),
                )
            }

        if (traceSink.isEnabled) {
            traceSink.index(
                IndexTraceEvent(
                    surface = surface,
                    phase = IndexTracePhase.Finish,
                    itemCount = items.size,
                    accountCount = metadata.accounts.size,
                    folderCount = metadata.folders.size,
                    tagCount = metadata.tags.size,
                    collectionCount = metadata.collections.size,
                    organizationCount = metadata.organizations.size,
                    documentCount = documents.size,
                    hotPostingCounts =
                        postings.entries.associate { (field, values) ->
                            field.displayName to values.size
                        },
                    reusedDocumentCount = reusedDocumentCount,
                    rebuiltDocumentCount = rebuiltDocumentCount,
                    removedDocumentCount = removedDocumentCount,
                    durationMs = buildStart.elapsedNow().inWholeMilliseconds,
                ),
            )
        }

        return createVaultSearchIndex(
            surface = surface,
            tokenizer = tokenizer,
            scorer = scorer,
            executor = executor,
            parser = parser,
            compiler = compiler,
            traceSink = traceSink,
            documents = documents,
            docIdsBySourceId = docIdsBySourceId,
            postings =
                postings.mapValues { entry ->
                    entry.value.mapValues { it.value.toList() }
                },
            fieldStats = stats,
            exactFacets = exactFacets,
            accountResolver = buildAccountResolver(metadata.accounts, tokenizer),
            folderResolver =
                buildNamedResolver(metadata.folders, tokenizer) { folder ->
                    listOf(folder.id, folder.name)
                },
            tagResolver = buildTagResolver(metadata.tags, tokenizer),
            organizationResolver =
                buildNamedResolver(metadata.organizations, tokenizer) { organization ->
                    listOf(organization.id, organization.name)
                },
            collectionResolver =
                buildNamedResolver(metadata.collections, tokenizer) { collection ->
                    listOf(collection.id, collection.name)
                },
        )
    }

    private fun buildDocument(
        docId: Int,
        secret: DSecret,
        tokenizer: SearchTokenizer,
    ): DocumentBuildResult {
        val hotValues =
            hotFieldValues(
                source = secret,
            )
        val hotFields = mutableMapOf<VaultTextField, VaultSearchFieldData>()
        val coldFields = mutableMapOf<VaultTextField, VaultSearchFieldData>()
        val hotPostings = mutableMapOf<VaultTextField, Map<String, Int>>()
        val fieldDocFrequencies = mutableMapOf<VaultTextField, Set<String>>()
        val fieldLengths = mutableMapOf<VaultTextField, Int>()

        hotValues.forEach { (field, values) ->
            val normalizedValues =
                values.mapNotNull { raw ->
                    val tokenization =
                        tokenizer.tokenize(
                            value = raw,
                            profile = field.profile(),
                        )
                    if (tokenization.terms.isEmpty()) {
                        return@mapNotNull null
                    }
                    buildIndexedFieldValue(
                        raw = raw,
                        tokenization = tokenization,
                        cachePerValueTerms = false,
                    ) to tokenization
                }
            val termFrequencies = mutableMapOf<String, Int>()
            val exactTermFrequencies = mutableMapOf<String, Int>()
            var totalTerms = 0
            normalizedValues.forEach { (_, tokenization) ->
                tokenization.terms.forEach { term ->
                    termFrequencies[term] = termFrequencies.getOrElse(term) { 0 } + 1
                    totalTerms += 1
                }
                tokenization.exactTerms.forEach { term ->
                    exactTermFrequencies[term] = exactTermFrequencies.getOrElse(term) { 0 } + 1
                }
            }
            hotFields[field] =
                VaultSearchFieldData(
                    values = normalizedValues.map { it.first },
                    termFrequencies = termFrequencies,
                    exactTermFrequencies = exactTermFrequencies,
                    totalTerms = totalTerms,
                )
            hotPostings[field] = termFrequencies
            fieldDocFrequencies[field] = termFrequencies.keys
            fieldLengths[field] = totalTerms
        }

        coldFieldValues(secret).forEach { (field, values) ->
            val normalizedValues =
                values.mapNotNull { raw ->
                    val tokenization =
                        tokenizer.tokenize(
                            value = raw,
                            profile = field.profile(),
                        )
                    if (tokenization.terms.isEmpty()) {
                        return@mapNotNull null
                    }
                    buildIndexedFieldValue(
                        raw = raw,
                        tokenization = tokenization,
                        cachePerValueTerms = true,
                    ) to tokenization
                }
            val termFrequencies = mutableMapOf<String, Int>()
            val exactTermFrequencies = mutableMapOf<String, Int>()
            var totalTerms = 0
            normalizedValues.forEach { (_, tokenization) ->
                tokenization.terms.forEach { term ->
                    termFrequencies[term] = termFrequencies.getOrElse(term) { 0 } + 1
                    totalTerms += 1
                }
                tokenization.exactTerms.forEach { term ->
                    exactTermFrequencies[term] = exactTermFrequencies.getOrElse(term) { 0 } + 1
                }
            }
            if (normalizedValues.isNotEmpty()) {
                coldFields[field] =
                    VaultSearchFieldData(
                        values = normalizedValues.map { it.first },
                        termFrequencies = termFrequencies,
                        exactTermFrequencies = exactTermFrequencies,
                        totalTerms = totalTerms,
                    )
                fieldDocFrequencies[field] = termFrequencies.keys
                fieldLengths[field] = totalTerms
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
}

internal fun buildIndexedFieldValue(
    raw: String,
    tokenization: SearchTokenization,
    cachePerValueTerms: Boolean,
): IndexedFieldValue = IndexedFieldValue(
    raw = raw,
    normalized = tokenization.normalizedText,
    exactNormalized = tokenization.exactNormalizedText,
    normalizedTerms = tokenization.terms.distinct().takeIf { cachePerValueTerms },
    exactNormalizedTerms = tokenization.exactTerms.distinct().takeIf { cachePerValueTerms },
)
