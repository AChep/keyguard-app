package com.artemchep.keyguard.feature.home.vault.search.engine

import com.artemchep.keyguard.common.model.DSecret

internal data class DocumentCacheUpdate(
    val nextDocId: Int,
    val documentsBySourceId: Map<String, CachedDocumentState>,
    val reusedDocumentCount: Int,
    val rebuiltDocumentCount: Int,
    val removedDocumentCount: Int,
) {
    val hasCorpusChanges: Boolean
        get() = rebuiltDocumentCount > 0 || removedDocumentCount > 0

    fun toCachedCorpusState(corpus: SearchCorpus): CachedCorpusState =
        CachedCorpusState(
            nextDocId = nextDocId,
            documentsBySourceId = documentsBySourceId,
            corpus = corpus,
        )
}

internal fun reconcileDocuments(
    previous: CachedCorpusState,
    items: List<DSecret>,
    dataRevCounters: Map<String, Long>,
    fingerprintOf: (DSecret) -> Int,
    buildDocument: (docId: Int, secret: DSecret) -> DocumentBuildResult,
): DocumentCacheUpdate {
    var nextDocId = previous.nextDocId
    var rebuiltDocumentCount = 0
    var reusedDocumentCount = 0
    val nextDocumentsBySourceId = LinkedHashMap<String, CachedDocumentState>(items.size)
    items.forEach { secret ->
        val previousDocument = previous.documentsBySourceId[secret.id]
        val dataRevCounter = dataRevCounters[secret.id]
        val nextDocument =
            if (
                dataRevCounter != null &&
                previousDocument?.dataRevCounter == dataRevCounter
            ) {
                reusedDocumentCount += 1
                previousDocument
            } else {
                val fingerprint = fingerprintOf(secret)
                if (previousDocument != null && previousDocument.fingerprint == fingerprint) {
                    reusedDocumentCount += 1
                    previousDocument.copy(
                        dataRevCounter = dataRevCounter,
                    )
                } else {
                    val docId = previousDocument?.buildResult?.document?.docId ?: nextDocId++
                    rebuiltDocumentCount += 1
                    CachedDocumentState(
                        fingerprint = fingerprint,
                        dataRevCounter = dataRevCounter,
                        buildResult = buildDocument(docId, secret),
                    )
                }
            }
        nextDocumentsBySourceId[secret.id] = nextDocument
    }
    val removedDocumentCount =
        (
            previous.documentsBySourceId.keys -
                nextDocumentsBySourceId.keys
        ).size
    return DocumentCacheUpdate(
        nextDocId = nextDocId,
        documentsBySourceId = nextDocumentsBySourceId,
        reusedDocumentCount = reusedDocumentCount,
        rebuiltDocumentCount = rebuiltDocumentCount,
        removedDocumentCount = removedDocumentCount,
    )
}
