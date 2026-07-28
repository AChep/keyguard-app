package com.artemchep.keyguard.feature.home.vault.search.engine

import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.search.query.VaultSearchQualifierCatalog
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledBooleanClause
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledColdTextClause
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledFacetClause
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledHotTextClause
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledQueryClause
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledQueryPlan
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultBooleanField
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultFacetField
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultSearchQueryCompiler
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultTextField
import com.artemchep.keyguard.feature.home.vault.search.query.model.ClauseNode
import com.artemchep.keyguard.feature.home.vault.search.query.parser.VaultSearchParser
import kotlin.time.TimeSource

internal fun createVaultSearchIndex(
    surface: String?,
    tokenizer: SearchTokenizer,
    scorer: SearchScorer,
    executor: SearchExecutor,
    parser: VaultSearchParser,
    compiler: VaultSearchQueryCompiler,
    traceSink: VaultSearchTraceSink,
    corpus: SearchCorpus,
    metadataResolvers: VaultSearchMetadataResolvers,
): VaultSearchIndex =
    DefaultVaultSearchIndex(
        surface = surface,
        tokenizer = tokenizer,
        scorer = scorer,
        executor = executor,
        parser = parser,
        compiler = compiler,
        traceSink = traceSink,
        documents = corpus.documents,
        docIdsBySourceId = corpus.docIdsBySourceId,
        postings = corpus.postings,
        fieldStats = corpus.fieldStats,
        exactFacets = corpus.exactFacets,
        accountResolver = metadataResolvers.account,
        folderResolver = metadataResolvers.folder,
        tagResolver = metadataResolvers.tag,
        organizationResolver = metadataResolvers.organization,
        collectionResolver = metadataResolvers.collection,
    )

private class DefaultVaultSearchIndex(
    private val surface: String?,
    private val tokenizer: SearchTokenizer,
    private val scorer: SearchScorer,
    private val executor: SearchExecutor,
    private val parser: VaultSearchParser,
    private val compiler: VaultSearchQueryCompiler,
    private val traceSink: VaultSearchTraceSink,
    private val documents: Map<Int, VaultSearchDocument>,
    private val docIdsBySourceId: Map<String, Int>,
    private val postings: Map<VaultTextField, Map<String, List<SearchPosting>>>,
    private val fieldStats: Map<VaultTextField, SearchFieldStats>,
    private val exactFacets: ExactFacetIndex,
    private val accountResolver: MetadataResolver,
    private val folderResolver: MetadataResolver,
    private val tagResolver: MetadataResolver,
    private val organizationResolver: MetadataResolver,
    private val collectionResolver: MetadataResolver,
) : SurfaceAwareVaultSearchIndex {
    override fun withSurface(surface: String?): VaultSearchIndex =
        if (this.surface == surface) {
            this
        } else {
            DefaultVaultSearchIndex(
                surface = surface,
                tokenizer = tokenizer,
                scorer = scorer,
                executor = executor,
                parser = parser,
                compiler = compiler,
                traceSink = traceSink,
                documents = documents,
                docIdsBySourceId = docIdsBySourceId,
                postings = postings,
                fieldStats = fieldStats,
                exactFacets = exactFacets,
                accountResolver = accountResolver,
                folderResolver = folderResolver,
                tagResolver = tagResolver,
                organizationResolver = organizationResolver,
                collectionResolver = collectionResolver,
            )
        }

    override fun compile(
        query: String,
        searchBy: VaultRoute.Args.SearchBy,
        qualifierCatalog: VaultSearchQualifierCatalog,
    ): CompiledQueryPlan? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        val compileStart = TimeSource.Monotonic.markNow()
        val parsed = parser.parse(trimmed)
        val plan =
            compiler.compile(
                query = parsed,
                searchBy = searchBy,
                qualifierCatalog = qualifierCatalog,
            )
        val compiledPlan = plan.takeIf { it.hasActiveClauses || it.diagnostics.isNotEmpty() }
        if (traceSink.isEnabled) {
            traceSink.query(
                QueryTraceEvent(
                    surface = surface,
                    rawQuery = trimmed,
                    searchBy = searchBy,
                    parsedClauses = parsed.clauses.map(ClauseNode::raw),
                    diagnostics =
                        parsed.diagnostics.map { diagnostic ->
                            "${diagnostic.severity.name.lowercase()}:${diagnostic.message}"
                        },
                    positiveClauses = plan.positiveClauses.map(CompiledQueryClause::describeForTrace),
                    negativeClauses = plan.negativeClauses.map(CompiledQueryClause::describeForTrace),
                    planId = compiledPlan?.id,
                    hasActiveClauses = plan.hasActiveClauses,
                    durationMs = compileStart.elapsedNow().inWholeMilliseconds,
                ),
            )
        }
        return compiledPlan
    }

    override suspend fun evaluate(
        plan: CompiledQueryPlan?,
        candidates: List<VaultItem2.Item>,
        highlightBackgroundColor: Color,
        highlightContentColor: Color,
    ): List<VaultItem2.Item> {
        if (plan == null) {
            return candidates
        }
        if (!plan.hasActiveClauses) {
            return if (plan.diagnostics.isNotEmpty()) {
                emptyList()
            } else {
                candidates
            }
        }
        val evaluationStart =
            if (traceSink.isEnabled) {
                TimeSource.Monotonic.markNow()
            } else {
                null
            }
        val traceEnabled = traceSink.isEnabled
        val candidateEntries = ArrayList<IndexedCandidate>(candidates.size)
        val initialDocIds = HashSet<Int>(collectionCapacity(candidates.size))
        candidates.forEachIndexed { order, item ->
            val docId = docIdsBySourceId[item.source.id] ?: return@forEachIndexed
            candidateEntries += IndexedCandidate(
                docId = docId,
                order = order,
                item = item,
            )
            initialDocIds += docId
        }
        val resolvedNegativeDocs = plan.negativeClauses.map { clause ->
            when (clause) {
                is CompiledFacetClause -> resolveFacetDocs(clause)
                is CompiledBooleanClause -> resolveBooleanDocs(clause, initialDocIds)
                is CompiledHotTextClause,
                is CompiledColdTextClause,
                -> null
            }
        }

        val activeDocIds = initialDocIds
        plan.positiveClauses.forEach { clause ->
            if (clause is CompiledFacetClause) {
                activeDocIds.retainAll(resolveFacetDocs(clause))
            }
        }
        val facetDocIds = activeDocIds.stageSnapshot(traceEnabled)
        plan.positiveClauses.forEach { clause ->
            if (clause is CompiledBooleanClause) {
                activeDocIds.retainAll(
                    resolveBooleanDocs(
                        clause = clause,
                        universe = activeDocIds,
                    ),
                )
            }
        }
        val booleanDocIds = activeDocIds.stageSnapshot(traceEnabled)
        plan.positiveClauses.forEach { clause ->
            if (clause is CompiledHotTextClause) {
                activeDocIds.retainAll(resolveHotClauseDocs(clause))
            }
        }
        val hotDocIds = activeDocIds.stageSnapshot(traceEnabled)

        val activeCandidates = candidateEntries.filter { it.docId in hotDocIds }
        val evaluations =
            executor
                .map(activeCandidates) { entry ->
                    val docId = entry.docId
                    val document =
                        documents[docId]
                            ?: return@map null

                    val negativeMatched =
                        plan.negativeClauses.indices.any { index ->
                            when (val clause = plan.negativeClauses[index]) {
                                is CompiledFacetClause,
                                is CompiledBooleanClause,
                                -> docId in requireNotNull(resolvedNegativeDocs[index])

                                is CompiledHotTextClause -> evaluateHotClause(document, clause) != null
                                is CompiledColdTextClause -> evaluateColdClause(document, clause) != null
                            }
                        }
                    if (negativeMatched) {
                        if (!traceEnabled) {
                            return@map null
                        }
                        val passedColdStage = plan.positiveClauses.all { clause ->
                            clause !is CompiledColdTextClause ||
                                evaluateColdClause(document, clause) != null
                        }
                        if (!passedColdStage) {
                            return@map null
                        }
                        return@map EvaluatedResult(
                            docId = docId,
                            item = entry.item,
                            score = 0.0,
                            exactMatchCount = 0,
                            order = entry.order,
                            titleTerms = emptySet(),
                            context = null,
                            negativeMatched = true,
                        )
                    }

                    var score = 0.0
                    var exactMatchCount = 0
                    var titleTerms: Set<String> = emptySet()
                    var context: MatchContext? = null
                    plan.positiveClauses.forEach { clause ->
                        val match =
                            when (clause) {
                                is CompiledFacetClause -> null
                                is CompiledBooleanClause -> null
                                is CompiledHotTextClause -> evaluateHotClause(document, clause)
                                is CompiledColdTextClause -> evaluateColdClause(document, clause)
                            } ?: if (clause is CompiledFacetClause || clause is CompiledBooleanClause) {
                                null
                            } else {
                                return@map null
                            }
                        if (match != null) {
                            score += match.score
                            exactMatchCount += match.exactMatchCount
                            if (match.titleTerms.isNotEmpty()) {
                                titleTerms = if (titleTerms.isEmpty()) {
                                    match.titleTerms
                                } else {
                                    titleTerms + match.titleTerms
                                }
                            }
                            val matchContext = match.context
                            if (matchContext != null && (context == null || matchContext.score > context.score)) {
                                context = matchContext
                            }
                        }
                    }
                    EvaluatedResult(
                        docId = docId,
                        item = entry.item,
                        score = score,
                        exactMatchCount = exactMatchCount,
                        order = entry.order,
                        titleTerms = titleTerms,
                        context = context,
                        negativeMatched = false,
                    )
                }.filterNotNull()

        val survivingEvaluations = if (traceEnabled) {
            evaluations.filterNot(EvaluatedResult::negativeMatched)
        } else {
            evaluations
        }
        val ordered =
            if (plan.hasScoringClauses) {
                survivingEvaluations.sortedWith(
                    compareByDescending<EvaluatedResult> { it.score }
                        .thenByDescending { it.exactMatchCount }
                        .thenBy { it.order },
                )
            } else {
                survivingEvaluations.sortedBy(EvaluatedResult::order)
            }

        if (traceEnabled) {
            val coldDocIds = evaluations.map(EvaluatedResult::docId).toSet()
            val survivedNegativeDocIds = survivingEvaluations.map(EvaluatedResult::docId).toSet()
            traceSink.evaluation(
                EvaluationTraceEvent(
                    surface = surface,
                    rawQuery = plan.rawQuery,
                    planId = plan.id,
                    rankingMode = rankingModeForTrace(plan.hasScoringClauses),
                    initialCandidateCount = candidateEntries.size,
                    afterFacetCount = facetDocIds.size,
                    afterBooleanCount = booleanDocIds.size,
                    afterHotCount = hotDocIds.size,
                    afterColdCount = coldDocIds.size,
                    afterNegativeCount = survivedNegativeDocIds.size,
                    finalResultCount = ordered.size,
                    durationMs = evaluationStart?.elapsedNow()?.inWholeMilliseconds ?: 0L,
                ),
            )
            candidateEntries.forEach { entry ->
                val docId = entry.docId
                val candidate = entry.item
                val document = documents[docId] ?: return@forEach
                val disposition =
                    when {
                        docId !in facetDocIds -> ItemTraceDisposition.DroppedByFacet
                        docId !in booleanDocIds -> ItemTraceDisposition.DroppedByBoolean
                        docId !in hotDocIds -> ItemTraceDisposition.DroppedByTextMiss
                        docId !in coldDocIds -> ItemTraceDisposition.DroppedByTextMiss
                        docId !in survivedNegativeDocIds -> ItemTraceDisposition.DroppedByNegativeClause
                        else -> ItemTraceDisposition.Kept
                    }
                traceSink.item(
                    ItemTraceEvent(
                        surface = surface,
                        rawQuery = plan.rawQuery,
                        planId = plan.id,
                        itemId = candidate.id,
                        sourceId = candidate.source.id,
                        type = candidate.source.type.name,
                        accountId = candidate.source.accountId,
                        folderId = candidate.source.folderId,
                        disposition = disposition,
                        clauses =
                            buildItemClauseTraces(
                                document = document,
                                plan = plan,
                            ),
                    ),
                )
            }
        }

        return ordered.map { evaluation ->
            decorateItem(
                item = evaluation.item,
                titleTerms = evaluation.titleTerms,
                context = evaluation.context,
                highlightBackgroundColor = highlightBackgroundColor,
                highlightContentColor = highlightContentColor,
            )
        }
    }

    private fun buildItemClauseTraces(
        document: VaultSearchDocument,
        plan: CompiledQueryPlan,
    ): List<ItemClauseTrace> =
        buildList {
            plan.positiveClauses.forEach { clause ->
                add(
                    clauseTrace(
                        document = document,
                        clause = clause,
                        negative = false,
                    ),
                )
            }
            plan.negativeClauses.forEach { clause ->
                add(
                    clauseTrace(
                        document = document,
                        clause = clause,
                        negative = true,
                    ),
                )
            }
        }

    private fun clauseTrace(
        document: VaultSearchDocument,
        clause: CompiledQueryClause,
        negative: Boolean,
    ): ItemClauseTrace =
        when (clause) {
            is CompiledFacetClause -> {
                ItemClauseTrace(
                    clause = clause.raw,
                    kind = clause.kindForTrace(),
                    stage = if (negative) "negative-clause" else clause.stageForTrace(),
                    matched = document.docId in resolveFacetDocs(clause),
                    matchedField = clause.fieldForTrace(),
                )
            }

            is CompiledBooleanClause -> {
                ItemClauseTrace(
                    clause = clause.raw,
                    kind = clause.kindForTrace(),
                    stage = if (negative) "negative-clause" else clause.stageForTrace(),
                    matched =
                        document.docId in
                            resolveBooleanDocs(
                                clause = clause,
                                universe = setOf(document.docId),
                            ),
                    matchedField = clause.fieldForTrace(),
                )
            }

            is CompiledHotTextClause -> {
                val probe =
                    probeHotClause(
                        document = document,
                        clause = clause,
                    )
                ItemClauseTrace(
                    clause = clause.raw,
                    kind = clause.kindForTrace(),
                    stage = if (negative) "negative-clause" else clause.stageForTrace(),
                    matched = probe.matched,
                    matchedField = probe.matchedField?.displayName ?: clause.fieldForTrace(),
                    matchedTermCount = probe.matchedTermCount,
                    phraseMatched = probe.phraseMatched,
                    scoreContribution = probe.score,
                    fieldPresence = probe.fieldPresence,
                    fieldTokenCount = probe.fieldTokenCount,
                )
            }

            is CompiledColdTextClause -> {
                val probe =
                    probeColdClause(
                        document = document,
                        clause = clause,
                    )
                ItemClauseTrace(
                    clause = clause.raw,
                    kind = clause.kindForTrace(),
                    stage = if (negative) "negative-clause" else clause.stageForTrace(),
                    matched = probe.matched,
                    matchedField = probe.matchedField?.displayName ?: clause.fieldForTrace(),
                    matchedTermCount = probe.matchedTermCount,
                    phraseMatched = probe.phraseMatched,
                    scoreContribution = probe.score,
                    fieldPresence = probe.fieldPresence,
                    fieldTokenCount = probe.fieldTokenCount,
                )
            }
        }

    private fun resolveFacetDocs(clause: CompiledFacetClause): Set<Int> =
        when (clause.field) {
            VaultFacetField.Account -> {
                clause.values.flatMapTo(mutableSetOf()) { value ->
                    resolveMetadataIds(accountResolver, value)
                        .flatMapTo(mutableSetOf()) { id -> exactFacets.account[id].orEmpty() }
                }
            }

            VaultFacetField.Folder -> {
                clause.values.flatMapTo(mutableSetOf()) { value ->
                    resolveMetadataIds(folderResolver, value)
                        .flatMapTo(mutableSetOf()) { id -> exactFacets.folder[id].orEmpty() }
                }
            }

            VaultFacetField.Tag -> {
                clause.values.flatMapTo(mutableSetOf()) { value ->
                    resolveTagIds(value)
                        .flatMapTo(mutableSetOf()) { id -> exactFacets.tag[id].orEmpty() }
                }
            }

            VaultFacetField.Organization -> {
                clause.values.flatMapTo(mutableSetOf()) { value ->
                    resolveMetadataIds(organizationResolver, value)
                        .flatMapTo(mutableSetOf()) { id -> exactFacets.organization[id].orEmpty() }
                }
            }

            VaultFacetField.Collection -> {
                clause.values.flatMapTo(mutableSetOf()) { value ->
                    resolveMetadataIds(collectionResolver, value)
                        .flatMapTo(mutableSetOf()) { id -> exactFacets.collection[id].orEmpty() }
                }
            }

            VaultFacetField.Type -> {
                clause.values.flatMapTo(mutableSetOf()) { value ->
                    exactFacets.type[value].orEmpty()
                }
            }
        }

    private fun resolveTagIds(value: String): Set<String> {
        val exact =
            buildSet {
                addAll(tagResolver.values[value].orEmpty())
                if (value in exactFacets.tag) {
                    add(value)
                }
            }
        if (exact.isNotEmpty()) {
            return exact
        }

        val normalizedValues =
            buildSet {
                addAll(tagResolver.fuzzyValues)
                addAll(exactFacets.tag.keys)
            }

        return normalizedValues
            .asSequence()
            .filter { normalizedTag -> normalizedTag.contains(value) }
            .flatMap { normalizedTag ->
                tagResolver.values[normalizedTag]
                    ?.asSequence()
                    ?: sequenceOf(normalizedTag)
            }.toSet()
            .ifEmpty { setOf(value) }
    }

    private fun resolveBooleanDocs(
        clause: CompiledBooleanClause,
        universe: Set<Int>,
    ): Set<Int> {
        val positives =
            when (clause.field) {
                VaultBooleanField.Favorite -> exactFacets.favorite
                VaultBooleanField.Reprompt -> exactFacets.reprompt
                VaultBooleanField.Otp -> exactFacets.otp
                VaultBooleanField.Attachments -> exactFacets.attachments
                VaultBooleanField.Passkeys -> exactFacets.passkeys
            }
        return if (clause.value) {
            positives
        } else {
            universe - positives
        }
    }

    private fun resolveHotClauseDocs(clause: CompiledHotTextClause): Set<Int> {
        val clauseDocs = HashSet<Int>()
        clause.fields.forEach { field ->
            val tokenization = clause.tokenizationFor(field)
            if (tokenization.terms.isEmpty()) {
                return@forEach
            }
            val fieldPostings = postings[field].orEmpty()
            var docsForField: HashSet<Int>? = null
            for (queryTerm in tokenization.terms) {
                val termDocIds = HashSet<Int>()
                fieldPostings.forEach { (indexedTerm, termPostings) ->
                    if (indexedTerm.contains(queryTerm)) {
                        termPostings.forEach { posting ->
                            termDocIds += posting.docId
                        }
                    }
                }
                docsForField = docsForField
                    ?.apply { retainAll(termDocIds) }
                    ?: termDocIds
                if (docsForField.isEmpty()) {
                    break
                }
            }
            docsForField?.forEach { docId ->
                val phraseMatched =
                    clause.rawPhrase == null ||
                        documents[docId]
                            ?.hotFields
                            ?.get(field)
                            ?.values
                            ?.any { value ->
                                value.normalized.contains(tokenization.normalizedText)
                            } == true
                if (phraseMatched) {
                    clauseDocs += docId
                }
            }
        }
        return clauseDocs
    }

    private fun evaluateHotClause(
        document: VaultSearchDocument,
        clause: CompiledHotTextClause,
    ): ClauseMatch? =
        probeHotClause(
            document = document,
            clause = clause,
        ).takeIf { it.matched }
            ?.toClauseMatch()

    private fun probeHotClause(
        document: VaultSearchDocument,
        clause: CompiledHotTextClause,
    ): ClauseProbe {
        var bestMatch: ClauseProbe? = null
        var titleMatched = false
        fieldLoop@ for (field in clause.fields) {
            val tokenization = clause.tokenizationFor(field)
            val queryTerms = tokenization.terms
            if (queryTerms.isEmpty()) {
                continue
            }
            val fieldData = document.hotFields[field] ?: continue
            val phraseMatched =
                clause.rawPhrase == null ||
                    fieldData.values.any { value ->
                        value.normalized.contains(tokenization.normalizedText)
                    }
            if (!phraseMatched) {
                continue
            }

            val fieldBoost = field.boost()
            val stats = fieldStats[field]
            var score = 0.0
            for (queryTerm in queryTerms) {
                var termMatched = false
                fieldData.termFrequencies.forEach { (indexedTerm, frequency) ->
                    if (indexedTerm.contains(queryTerm)) {
                        termMatched = true
                        if (stats != null) {
                            score += scorer.score(
                                SearchScoreParams(
                                    termFrequency = frequency,
                                    documentFrequency = stats.documentFrequency[indexedTerm] ?: 0,
                                    documentLength = fieldData.totalTerms,
                                    averageDocumentLength = stats.averageLength,
                                    documentCount = documents.size,
                                    fieldBoost = fieldBoost,
                                ),
                            )
                        }
                    }
                }
                if (!termMatched) {
                    continue@fieldLoop
                }
            }

            var exactTermCount = 0
            tokenization.exactTerms.forEach { queryTerm ->
                if (fieldData.exactTermFrequencies.keys.any { indexedTerm ->
                        indexedTerm.contains(queryTerm)
                    }
                ) {
                    exactTermCount += 1
                }
            }
            val exactPhraseMatched =
                clause.rawPhrase != null &&
                    tokenization.exactNormalizedText.isNotBlank() &&
                    fieldData.values.any { value ->
                        value.exactNormalized.contains(tokenization.exactNormalizedText)
                    }
            val exactMatchCount = exactTermCount + if (exactPhraseMatched) 1 else 0
            if (clause.rawPhrase != null) {
                score += fieldBoost * 0.5
            }
            score += exactMatchBonus(
                field = field,
                exactTermCount = exactTermCount,
                exactPhraseMatched = exactPhraseMatched,
            )
            val context =
                if (field != VaultTextField.Title) {
                    fieldData.values
                        .firstOrNull { value ->
                            queryTerms.all { term -> value.normalized.contains(term) }
                        }?.raw
                        ?.let { rawValue ->
                            MatchContext(
                                field = field,
                                snippet =
                                    snippetForField(
                                        field = field,
                                        source = document.source,
                                        value = rawValue,
                                    ),
                                score = score,
                            )
                        }
                } else {
                    null
                }
            val match = ClauseProbe(
                matched = true,
                matchedField = field,
                matchedTermCount = queryTerms.size,
                exactMatchCount = exactMatchCount,
                phraseMatched = phraseMatched,
                score = score,
                fieldPresence = true,
                fieldTokenCount = fieldData.totalTerms,
                context = context,
            )
            if (field == VaultTextField.Title) {
                titleMatched = true
            }
            if (bestMatch == null || match.score > bestMatch.score) {
                bestMatch = match
            }
        }
        bestMatch?.let { match ->
            return match.copy(
                titleTerms = clause.titleHighlightTerms.takeIf { titleMatched }.orEmpty(),
            )
        }
        return ClauseProbe(
            matched = false,
            matchedField = null,
            matchedTermCount = 0,
            phraseMatched = false,
            score = 0.0,
            fieldPresence = clause.fields.any { document.hotFields[it] != null },
            fieldTokenCount =
                clause.fields
                    .sumOf { field ->
                        document.hotFields[field]?.totalTerms ?: 0
                    }.takeIf { it > 0 },
        )
    }

    private fun evaluateColdClause(
        document: VaultSearchDocument,
        clause: CompiledColdTextClause,
    ): ClauseMatch? =
        probeColdClause(
            document = document,
            clause = clause,
        ).takeIf { it.matched }
            ?.toClauseMatch()

    private fun probeColdClause(
        document: VaultSearchDocument,
        clause: CompiledColdTextClause,
    ): ClauseProbe {
        val fieldData =
            document.coldFields[clause.field]
                ?: return ClauseProbe(
                    matched = false,
                    matchedField = null,
                    matchedTermCount = 0,
                    phraseMatched = false,
                    score = 0.0,
                    fieldPresence = false,
                    fieldTokenCount = null,
                )
        val queryTerms = clause.tokenization.terms
        val exactQueryTerms = clause.tokenization.exactTerms
        val phraseMatched =
            clause.rawPhrase == null ||
                fieldData.values.any { value ->
                    value.normalized.contains(clause.tokenization.normalizedText)
                }
        if (!phraseMatched) {
            return ClauseProbe(
                matched = false,
                fieldPresence = true,
                fieldTokenCount = fieldData.totalTerms.takeIf { it > 0 },
            )
        }
        val stats = fieldStats[clause.field]
        val fieldBoost = clause.field.boost()
        var bestMatch: ClauseProbe? = null
        valueLoop@ for (value in fieldData.values) {
            val normalizedTerms =
                value.normalizedTerms
                    ?: value.normalized
                        .split(' ')
                        .filter(String::isNotBlank)
                        .distinct()
            val exactNormalizedTerms =
                value.exactNormalizedTerms
                    ?: value.exactNormalized
                        .split(' ')
                        .filter(String::isNotBlank)
                        .distinct()
            var score = 0.0
            for (queryTerm in queryTerms) {
                var termMatched = false
                normalizedTerms.forEach { indexedTerm ->
                    if (indexedTerm.contains(queryTerm)) {
                        termMatched = true
                        score += scorer.score(
                            SearchScoreParams(
                                termFrequency = fieldData.termFrequencies[indexedTerm] ?: 0,
                                documentFrequency = stats?.documentFrequency?.get(indexedTerm) ?: 0,
                                documentLength = fieldData.totalTerms,
                                averageDocumentLength = stats?.averageLength ?: 0.0,
                                documentCount = documents.size,
                                fieldBoost = fieldBoost,
                            ),
                        )
                    }
                }
                if (!termMatched) {
                    continue@valueLoop
                }
            }
            var exactTermCount = 0
            exactQueryTerms.forEach { queryTerm ->
                if (exactNormalizedTerms.any { indexedTerm -> indexedTerm.contains(queryTerm) }) {
                    exactTermCount += 1
                }
            }
            val exactPhraseMatched =
                clause.rawPhrase != null &&
                    clause.tokenization.exactNormalizedText.isNotBlank() &&
                    value.exactNormalized.contains(clause.tokenization.exactNormalizedText)
            val exactMatchCount = exactTermCount + if (exactPhraseMatched) 1 else 0
            if (clause.rawPhrase != null) {
                score += fieldBoost * 0.5
            }
            score += exactMatchBonus(
                field = clause.field,
                exactTermCount = exactTermCount,
                exactPhraseMatched = exactPhraseMatched,
            )
            val match = ClauseProbe(
                matched = true,
                matchedField = clause.field,
                matchedTermCount = queryTerms.size,
                exactMatchCount = exactMatchCount,
                phraseMatched = phraseMatched,
                score = score,
                fieldPresence = true,
                fieldTokenCount = fieldData.totalTerms,
                context =
                    MatchContext(
                        field = clause.field,
                        snippet =
                            snippetForField(
                                field = clause.field,
                                source = document.source,
                                value = value.raw,
                            ),
                        score = score,
                    ),
            )
            if (bestMatch == null || match.score > bestMatch.score) {
                bestMatch = match
            }
        }
        return bestMatch ?: ClauseProbe(
            matched = false,
            matchedField = null,
            matchedTermCount = 0,
            phraseMatched = false,
            score = 0.0,
            fieldPresence = true,
            fieldTokenCount = fieldData.totalTerms.takeIf { it > 0 },
        )
    }

    private fun ClauseProbe.toClauseMatch(): ClauseMatch =
        ClauseMatch(
            score = score,
            exactMatchCount = exactMatchCount,
            titleTerms = titleTerms,
            context = context,
            trace = this,
        )

    private fun exactMatchBonus(
        field: VaultTextField,
        exactTermCount: Int,
        exactPhraseMatched: Boolean,
    ): Double {
        if (exactTermCount == 0 && !exactPhraseMatched) {
            return 0.0
        }
        return (exactTermCount * field.boost() * 0.35) +
            if (exactPhraseMatched) field.boost() * 0.15 else 0.0
    }

    private fun decorateItem(
        item: VaultItem2.Item,
        titleTerms: Set<String>,
        context: MatchContext?,
        highlightBackgroundColor: Color,
        highlightContentColor: Color,
    ): VaultItem2.Item {
        if (
            titleTerms.isEmpty() &&
            context == null &&
            item.searchContextBadge == null
        ) {
            return item
        }
        val newTitle =
            if (titleTerms.isNotEmpty()) {
                highlightTitle(
                    text = item.title.text,
                    terms = titleTerms,
                    highlightBackgroundColor = highlightBackgroundColor,
                    highlightContentColor = highlightContentColor,
                )
            } else {
                item.title
            }
        val newText = item.text
        val newSearchContextBadge =
            context
                ?.let {
                    VaultItem2.Item.SearchContextBadge(
                        field = it.field,
                        text = it.snippet,
                    )
                }
        return item.copy(
            title = newTitle,
            text = newText,
            searchContextBadge = newSearchContextBadge,
        )
    }
}

private data class IndexedCandidate(
    val docId: Int,
    val order: Int,
    val item: VaultItem2.Item,
)

private fun collectionCapacity(size: Int): Int =
    if (size < 3) {
        size + 1
    } else {
        (size / 0.75f + 1.0f).toInt()
    }

private fun MutableSet<Int>.stageSnapshot(enabled: Boolean): Set<Int> =
    if (enabled) toSet() else this
