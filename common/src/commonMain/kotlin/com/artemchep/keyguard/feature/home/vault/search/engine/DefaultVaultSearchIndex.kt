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
    documents: Map<Int, VaultSearchDocument>,
    docIdsBySourceId: Map<String, Int>,
    postings: Map<VaultTextField, Map<String, List<SearchPosting>>>,
    fieldStats: Map<VaultTextField, SearchFieldStats>,
    exactFacets: ExactFacetIndex,
    accountResolver: MetadataResolver,
    folderResolver: MetadataResolver,
    tagResolver: MetadataResolver,
    organizationResolver: MetadataResolver,
    collectionResolver: MetadataResolver,
): VaultSearchIndex =
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
        val candidateEntries =
            candidates.mapIndexedNotNull { order, item ->
                val docId =
                    docIdsBySourceId[item.source.id]
                        ?: return@mapIndexedNotNull null
                docId to (order to item)
            }
        val initialDocIds = candidateEntries.map { it.first }.toSet()
        var facetDocIds = initialDocIds
        plan.positiveClauses.filterIsInstance<CompiledFacetClause>().forEach { clause ->
            facetDocIds = facetDocIds intersect resolveFacetDocs(clause)
        }
        var booleanDocIds = facetDocIds
        plan.positiveClauses.filterIsInstance<CompiledBooleanClause>().forEach { clause ->
            booleanDocIds = booleanDocIds intersect
                resolveBooleanDocs(
                    clause = clause,
                    universe = booleanDocIds,
                )
        }
        var hotDocIds = booleanDocIds
        plan.positiveClauses.filterIsInstance<CompiledHotTextClause>().forEach { clause ->
            hotDocIds = hotDocIds intersect resolveHotClauseDocs(clause)
        }

        val activeCandidates = candidateEntries.filter { it.first in hotDocIds }
        val evaluations =
            executor
                .map(activeCandidates) { (docId, pair) ->
                    val (order, candidate) = pair
                    val document =
                        documents[docId]
                            ?: return@map null

                    val positiveMatches = mutableListOf<ClauseMatch>()
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
                            positiveMatches += match
                        }
                    }

                    val negativeMatched =
                        plan.negativeClauses.any { clause ->
                            when (clause) {
                                is CompiledFacetClause -> {
                                    docId in resolveFacetDocs(clause)
                                }

                                is CompiledBooleanClause -> {
                                    docId in
                                        resolveBooleanDocs(
                                            clause = clause,
                                            universe = setOf(docId),
                                        )
                                }

                                is CompiledHotTextClause -> {
                                    evaluateHotClause(document, clause) != null
                                }

                                is CompiledColdTextClause -> {
                                    evaluateColdClause(document, clause) != null
                                }
                            }
                        }
                    if (negativeMatched) {
                        return@map EvaluatedResult(
                            docId = docId,
                            item = candidate,
                            score = 0.0,
                            exactMatchCount = 0,
                            order = order,
                            titleTerms = emptySet(),
                            context = null,
                            negativeMatched = true,
                        )
                    }

                    val score = positiveMatches.sumOf(ClauseMatch::score)
                    val exactMatchCount = positiveMatches.sumOf(ClauseMatch::exactMatchCount)
                    val titleTerms =
                        positiveMatches
                            .flatMap { it.titleTerms }
                            .toSet()
                    val context =
                        positiveMatches
                            .mapNotNull { it.context }
                            .maxByOrNull { it.score }
                    EvaluatedResult(
                        docId = docId,
                        item = candidate,
                        score = score,
                        exactMatchCount = exactMatchCount,
                        order = order,
                        titleTerms = titleTerms,
                        context = context,
                        negativeMatched = false,
                    )
                }.filterNotNull()

        val coldDocIds = evaluations.map(EvaluatedResult::docId).toSet()
        val survivedNegativeDocIds =
            evaluations
                .mapNotNull { evaluation ->
                    evaluation.docId.takeIf { !evaluation.negativeMatched }
                }.toSet()

        val ordered =
            if (plan.hasScoringClauses) {
                evaluations.sortedWith(
                    compareByDescending<EvaluatedResult> { it.score }
                        .thenByDescending { it.exactMatchCount }
                        .thenBy { it.order },
                )
            } else {
                evaluations.sortedBy(EvaluatedResult::order)
            }.filterNot(EvaluatedResult::negativeMatched)

        if (traceSink.isEnabled) {
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
            candidateEntries.forEach { (docId, pair) ->
                val candidate = pair.second
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
        val clauseDocs = mutableSetOf<Int>()
        clause.fields.forEach { field ->
            val tokenization = clause.tokenizationFor(field)
            if (tokenization.terms.isEmpty()) {
                return@forEach
            }
            val fieldPostings = postings[field].orEmpty()
            val docsForField =
                tokenization.terms
                    .distinct()
                    .map { term ->
                        fieldPostings
                            .matchingPostings(term)
                            .map(SearchPosting::docId)
                            .toSet()
                    }.reduceOrNull { acc, set -> acc intersect set }
                    .orEmpty()
                    .filterTo(mutableSetOf()) { docId ->
                        if (clause.rawPhrase == null) {
                            true
                        } else {
                            documents[docId]
                                ?.hotFields
                                ?.get(field)
                                ?.values
                                ?.any { value ->
                                    value.normalized.contains(tokenization.normalizedText)
                                } == true
                        }
                    }
            clauseDocs += docsForField
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
        val matches =
            clause.fields
                .mapNotNull { field ->
                    val tokenization = clause.tokenizationFor(field)
                    val queryTerms = tokenization.terms.distinct()
                    val exactQueryTerms = tokenization.exactTerms.distinct()
                    if (queryTerms.isEmpty()) {
                        return@mapNotNull null
                    }
                    val fieldData =
                        document.hotFields[field]
                            ?: return@mapNotNull null
                    val phraseMatched =
                        clause.rawPhrase == null ||
                            fieldData.values.any { value ->
                                value.normalized.contains(tokenization.normalizedText)
                            }
                    val matchedTerms =
                        queryTerms
                            .associateWith { term ->
                                fieldData.termFrequencies
                                    .keys
                                    .matchingTerms(term)
                            }
                    val exactMatchedTerms =
                        exactQueryTerms
                            .associateWith { term ->
                                fieldData.exactTermFrequencies
                                    .keys
                                    .matchingTerms(term)
                            }
                    if (matchedTerms.any { (_, terms) -> terms.isEmpty() } || !phraseMatched) {
                        return@mapNotNull null
                    }
                    val exactPhraseMatched =
                        clause.rawPhrase != null &&
                            tokenization.exactNormalizedText.isNotBlank() &&
                            fieldData.values.any { value ->
                                value.exactNormalized.contains(tokenization.exactNormalizedText)
                            }
                    val exactMatchCount =
                        exactMatchedTerms.count { (_, terms) -> terms.isNotEmpty() } +
                            if (exactPhraseMatched) 1 else 0
                    val score =
                        tokenization.terms
                            .distinct()
                            .sumOf { term ->
                                val stats = fieldStats[field] ?: return@sumOf 0.0
                                matchedTerms
                                    .getValue(term)
                                    .sumOf { matchedTerm ->
                                        scorer.score(
                                            SearchScoreParams(
                                                termFrequency = fieldData.termFrequencies[matchedTerm] ?: 0,
                                                documentFrequency = stats.documentFrequency[matchedTerm] ?: 0,
                                                documentLength = fieldData.totalTerms,
                                                averageDocumentLength = stats.averageLength,
                                                documentCount = documents.size,
                                                fieldBoost = field.boost(),
                                            ),
                                        )
                                    }
                            } + if (clause.rawPhrase != null) field.boost() * 0.5 else 0.0
                    +exactMatchBonus(
                        field = field,
                        exactMatchedTerms = exactMatchedTerms,
                        exactPhraseMatched = exactPhraseMatched,
                    )
                    val context =
                        if (field != VaultTextField.Title) {
                            fieldData.values
                                .firstOrNull { value ->
                                    queryTerms.all { term ->
                                        value.normalized.contains(term)
                                    }
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
                    ClauseProbe(
                        matched = true,
                        matchedField = field,
                        matchedTermCount = matchedTerms.size,
                        exactMatchCount = exactMatchCount,
                        phraseMatched = phraseMatched,
                        score = score,
                        fieldPresence = true,
                        fieldTokenCount = fieldData.totalTerms,
                        titleTerms =
                            if (field == VaultTextField.Title) {
                                matchedTerms
                                    .filterValues { terms -> terms.isNotEmpty() }
                                    .keys
                                    .toSet()
                            } else {
                                emptySet()
                            },
                        context = context,
                    )
                }
        val titleTerms = matches
            .flatMap(ClauseProbe::titleTerms)
            .toSet()
        val bestMatch = matches
            .maxByOrNull(ClauseProbe::score)
            ?.copy(titleTerms = titleTerms)
        return bestMatch ?: ClauseProbe(
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
        val queryTerms = clause.tokenization.terms.distinct()
        val exactQueryTerms = clause.tokenization.exactTerms.distinct()
        val phraseMatched =
            clause.rawPhrase == null ||
                fieldData.values.any { value ->
                    value.normalized.contains(clause.tokenization.normalizedText)
                }
        val bestMatch =
            fieldData.values
                .mapNotNull { value ->
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
                    val matchedTerms =
                        queryTerms
                            .associateWith { term ->
                                normalizedTerms.matchingTerms(term)
                            }
                    val exactMatchedTerms =
                        exactQueryTerms
                            .associateWith { term ->
                                exactNormalizedTerms.matchingTerms(term)
                            }
                    if (matchedTerms.any { (_, terms) -> terms.isEmpty() } || !phraseMatched) {
                        return@mapNotNull null
                    }
                    val stats = fieldStats[clause.field]
                    val exactPhraseMatched =
                        clause.rawPhrase != null &&
                            clause.tokenization.exactNormalizedText.isNotBlank() &&
                            value.exactNormalized.contains(clause.tokenization.exactNormalizedText)
                    val exactMatchCount =
                        exactMatchedTerms.count { (_, terms) -> terms.isNotEmpty() } +
                            if (exactPhraseMatched) 1 else 0
                    val score =
                        clause.tokenization.terms
                            .distinct()
                            .sumOf { term ->
                                matchedTerms
                                    .getValue(term)
                                    .distinct()
                                    .sumOf { matchedTerm ->
                                        scorer.score(
                                            SearchScoreParams(
                                                termFrequency = fieldData.termFrequencies[matchedTerm] ?: 0,
                                                documentFrequency = stats?.documentFrequency?.get(matchedTerm) ?: 0,
                                                documentLength = fieldData.totalTerms,
                                                averageDocumentLength = stats?.averageLength ?: 0.0,
                                                documentCount = documents.size,
                                                fieldBoost = clause.field.boost(),
                                            ),
                                        )
                                    }
                            } + if (clause.rawPhrase != null) clause.field.boost() * 0.5 else 0.0
                    +exactMatchBonus(
                        field = clause.field,
                        exactMatchedTerms = exactMatchedTerms,
                        exactPhraseMatched = exactPhraseMatched,
                    )
                    ClauseProbe(
                        matched = true,
                        matchedField = clause.field,
                        matchedTermCount = matchedTerms.size,
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
                }.maxByOrNull(ClauseProbe::score)
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

    private fun Map<String, List<SearchPosting>>.matchingPostings(queryTerm: String): List<SearchPosting> =
        entries
            .asSequence()
            .filter { (indexedTerm, _) -> indexedTerm.contains(queryTerm) }
            .flatMap { (_, postings) -> postings.asSequence() }
            .toList()

    private fun Collection<String>.matchingTerms(queryTerm: String): Set<String> =
        asSequence()
            .filter { indexedTerm -> indexedTerm.contains(queryTerm) }
            .toSet()

    private fun exactMatchBonus(
        field: VaultTextField,
        exactMatchedTerms: Map<String, Set<String>>,
        exactPhraseMatched: Boolean,
    ): Double {
        val exactTermCount = exactMatchedTerms.count { (_, terms) -> terms.isNotEmpty() }
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
