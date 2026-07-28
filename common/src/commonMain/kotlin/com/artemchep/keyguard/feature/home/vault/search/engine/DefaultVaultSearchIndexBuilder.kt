package com.artemchep.keyguard.feature.home.vault.search.engine

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultSearchQueryCompiler
import com.artemchep.keyguard.feature.home.vault.search.query.parser.VaultSearchParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

internal class DefaultVaultSearchIndexBuilder(
    private val tokenizer: SearchTokenizer,
    private val scorer: SearchScorer,
    private val executor: SearchExecutor,
    private val parser: VaultSearchParser,
    private val compiler: VaultSearchQueryCompiler,
    private val traceSink: VaultSearchTraceSink = NoOpVaultSearchTraceSink,
    private val fingerprintOf: (DSecret) -> Int = ::searchFingerprint,
) : VaultSearchIndexBuilder {
    private val documentIndexer = VaultSearchDocumentIndexer(tokenizer)
    private val cacheMutex = Mutex()
    private var cachedCorpusState = CachedCorpusState()

    override suspend fun build(
        items: List<DSecret>,
        metadata: VaultSearchIndexMetadata,
        surface: String?,
        dataRevCounters: Map<String, Long>,
    ): VaultSearchIndex =
        cacheMutex.withLock {
            buildLocked(
                items = items,
                metadata = metadata,
                surface = surface,
                dataRevCounters = dataRevCounters,
            )
        }

    private fun buildLocked(
        items: List<DSecret>,
        metadata: VaultSearchIndexMetadata,
        surface: String?,
        dataRevCounters: Map<String, Long>,
    ): VaultSearchIndex {
        traceIndexStart(
            items = items,
            metadata = metadata,
            surface = surface,
        )
        val buildStart = TimeSource.Monotonic.markNow()
        val cacheUpdate =
            reconcileDocuments(
                previous = cachedCorpusState,
                items = items,
                dataRevCounters = dataRevCounters,
                fingerprintOf = fingerprintOf,
                buildDocument = documentIndexer::build,
            )
        val corpus =
            cachedCorpusState.corpus
                ?.takeUnless { cacheUpdate.hasCorpusChanges }
                ?: buildSearchCorpus(
                    buildResults =
                        cacheUpdate.documentsBySourceId.values
                            .map(CachedDocumentState::buildResult),
                    tokenizer = tokenizer,
                )
        cachedCorpusState = cacheUpdate.toCachedCorpusState(corpus)

        traceIndexFinish(
            items = items,
            metadata = metadata,
            surface = surface,
            corpus = corpus,
            cacheUpdate = cacheUpdate,
            durationMs = buildStart.elapsedNow().inWholeMilliseconds,
        )

        return createVaultSearchIndex(
            surface = surface,
            tokenizer = tokenizer,
            scorer = scorer,
            executor = executor,
            parser = parser,
            compiler = compiler,
            traceSink = traceSink,
            corpus = corpus,
            metadataResolvers = buildMetadataResolvers(metadata, tokenizer),
        )
    }

    private fun traceIndexStart(
        items: List<DSecret>,
        metadata: VaultSearchIndexMetadata,
        surface: String?,
    ) {
        if (!traceSink.isEnabled) {
            return
        }
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

    private fun traceIndexFinish(
        items: List<DSecret>,
        metadata: VaultSearchIndexMetadata,
        surface: String?,
        corpus: SearchCorpus,
        cacheUpdate: DocumentCacheUpdate,
        durationMs: Long,
    ) {
        if (!traceSink.isEnabled) {
            return
        }
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
                documentCount = corpus.documents.size,
                hotPostingCounts =
                    corpus.postings.entries.associate { (field, values) ->
                        field.displayName to values.size
                    },
                reusedDocumentCount = cacheUpdate.reusedDocumentCount,
                rebuiltDocumentCount = cacheUpdate.rebuiltDocumentCount,
                removedDocumentCount = cacheUpdate.removedDocumentCount,
                durationMs = durationMs,
            ),
        )
    }
}
