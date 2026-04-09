package com.artemchep.keyguard.android.autofill.v2.resolve

import android.view.View
import android.view.autofill.AutofillId
import com.artemchep.keyguard.android.autofill.v2.analyzer.FieldAnalyzer
import com.artemchep.keyguard.android.autofill.v2.analyzer.FormAnalyzer
import com.artemchep.keyguard.android.autofill.v2.analyzer.impl.AutocompleteFieldAnalyzer
import com.artemchep.keyguard.android.autofill.v2.analyzer.impl.GeneratedMetaClassifier
import com.artemchep.keyguard.android.autofill.v2.analyzer.impl.HtmlTypeFieldAnalyzer
import com.artemchep.keyguard.android.autofill.v2.analyzer.impl.InputTypeFieldAnalyzer
import com.artemchep.keyguard.android.autofill.v2.analyzer.impl.TemplateFormAnalyzer
import com.artemchep.keyguard.android.autofill.v2.analyzer.impl.TextSignalFieldAnalyzer
import com.artemchep.keyguard.android.autofill.v2.analyzer.impl.TreeFieldAnalyzer
import com.artemchep.keyguard.android.autofill.v2.model.AnalysisContext
import com.artemchep.keyguard.android.autofill.v2.model.FieldCluster
import com.artemchep.keyguard.android.autofill.v2.model.FieldNode
import com.artemchep.keyguard.android.autofill.v2.model.FieldProposal
import com.artemchep.keyguard.android.autofill.v2.model.FormIntent
import com.artemchep.keyguard.android.autofill.v2.model.FormProposal
import com.artemchep.keyguard.android.autofill.v2.model.NormalizedStructureV2
import com.artemchep.keyguard.android.autofill.v2.model.ParseDebugResultV2
import com.artemchep.keyguard.android.autofill.v2.model.ParseOptions
import com.artemchep.keyguard.android.autofill.v2.model.ParseResultV2
import com.artemchep.keyguard.android.autofill.v2.model.SemanticType
import com.artemchep.keyguard.android.autofill.v2.policy.ParserPolicy
import com.artemchep.keyguard.android.autofill.v2.policy.PolicyResult
import com.artemchep.keyguard.android.autofill.v2.policy.impl.AuthSuppressionPolicy
import com.artemchep.keyguard.android.autofill.v2.util.EMAIL_KEYWORDS
import com.artemchep.keyguard.android.autofill.v2.util.EXPLICIT_EMAIL_NAME_ID_REGEX
import com.artemchep.keyguard.android.autofill.v2.util.KeywordMatcher
import com.artemchep.keyguard.android.autofill.v2.util.KeywordTag
import com.artemchep.keyguard.android.autofill.v2.util.LOGIN_IDENTIFIER_REGEX
import com.artemchep.keyguard.android.autofill.v2.util.OTP_KEYWORDS
import com.artemchep.keyguard.android.autofill.v2.util.PASSWORD_TOKEN_REGEX
import com.artemchep.keyguard.android.autofill.v2.util.PHONE_KEYWORDS
import com.artemchep.keyguard.android.autofill.v2.util.RESET_KEYWORDS
import com.artemchep.keyguard.android.autofill.v2.util.STRONG_USERNAME_KEYWORDS
import com.artemchep.keyguard.android.autofill.v2.util.USERNAME_KEYWORDS
import com.artemchep.keyguard.android.autofill.v2.util.autocompleteBlob
import com.artemchep.keyguard.android.autofill.v2.util.containsAny
import com.artemchep.keyguard.android.autofill.v2.util.fieldBlob
import com.artemchep.keyguard.android.autofill.v2.util.has
import com.artemchep.keyguard.android.autofill.v2.util.nameIdBlob
import kotlin.math.exp
import kotlin.math.ln

/**
 * Two-pass Bayesian resolver.
 *
 * **Pass 1** — Runs field and form analyzers, determines form intent per cluster.
 * **Pass 2** — Re-scores field types using [FormContextPrior] adjustments,
 * applies [normalizeSemanticType] for field-level corrections, and picks
 * the best type per field.
 */
class DefaultStructureResolverV2(
    private val fieldAnalyzers: List<FieldAnalyzer> =
        listOf(
            AutocompleteFieldAnalyzer(),
            HtmlTypeFieldAnalyzer(),
            InputTypeFieldAnalyzer(),
            TextSignalFieldAnalyzer(),
            TreeFieldAnalyzer(),
        ),
    private val formAnalyzers: List<FormAnalyzer> =
        listOf(
            TemplateFormAnalyzer(),
        ),
    private val policies: List<ParserPolicy> =
        listOf(
            AuthSuppressionPolicy(),
        ),
) : StructureResolverV2 {
    override fun resolve(
        clustered: NormalizedStructureV2,
        options: ParseOptions,
    ): ParseDebugResultV2 {
        val context = AnalysisContext(clustered, options)

        // ── Pass 1: run all analyzers ────────────────────────────────── //
        val fieldProposals =
            clustered.fields.associate { field ->
                field.id to fieldAnalyzers.flatMap { it.analyze(field, context) }
            }
        val formProposals =
            clustered.clusters.associate { cluster ->
                cluster.id to formAnalyzers.flatMap { it.analyze(cluster, context) }
            }

        val resolvedFormIntents = mutableMapOf<String, FormIntent>()
        val resolvedFieldTypes = mutableMapOf<AutofillId, SemanticType>()
        val notes = mutableListOf<String>()

        // Determine form intent per cluster (end of Pass 1).
        clustered.clusters.forEach { cluster ->
            val clusterFieldProposals =
                cluster.fieldIds.associateWith { fieldProposals[it].orEmpty() }
            val clusterFormProposals = formProposals[cluster.id].orEmpty()
            val mergedPolicy =
                mergePolicies(cluster, clusterFieldProposals, clusterFormProposals)
            val formIntent = selectFormIntent(clusterFormProposals, mergedPolicy)
            resolvedFormIntents[cluster.id] = formIntent
            notes += mergedPolicy.reasons

            // ── Pass 2: classify fields using meta-classifier ────── //
            val isFocusedCluster =
                options.focusedFieldId != null &&
                        options.focusedFieldId in cluster.fieldIds
            cluster.fieldIds.forEach { fieldId ->
                val field = context.fieldsById[fieldId] ?: return@forEach
                val proposals =
                    applyPolicy(fieldProposals[fieldId].orEmpty(), fieldId, mergedPolicy)
                val focusBoost =
                    when {
                        fieldId == options.focusedFieldId -> FOCUS_BOOST_FIELD
                        isFocusedCluster -> FOCUS_BOOST_CLUSTER
                        else -> 1.0
                    }
                val selected =
                    selectFieldType(
                        field = field,
                        proposals = proposals,
                        formIntent = formIntent,
                        context = context,
                        options = options,
                        focusBoost = focusBoost,
                    ) ?: return@forEach
                resolvedFieldTypes[fieldId] = selected
            }
        }

        return ParseDebugResultV2(
            result =
                ParseResultV2(
                    structure = clustered,
                    fieldTypes = resolvedFieldTypes,
                    formIntents = resolvedFormIntents,
                ),
            fieldProposals = fieldProposals,
            formProposals = formProposals,
            notes = notes.distinct(),
        )
    }

    // ------------------------------------------------------------------ //
    //  Policy helpers (unchanged)
    // ------------------------------------------------------------------ //

    private fun mergePolicies(
        cluster: FieldCluster,
        fieldProposals: Map<AutofillId, List<FieldProposal>>,
        formProposals: List<FormProposal>,
    ): PolicyResult =
        policies.fold(PolicyResult()) { acc, policy ->
            val next = policy.apply(cluster, fieldProposals, formProposals)
            PolicyResult(
                suppressedFieldTypes = acc.suppressedFieldTypes + next.suppressedFieldTypes,
                boostedFieldTypes = acc.boostedFieldTypes + next.boostedFieldTypes,
                suppressedFormIntents = acc.suppressedFormIntents + next.suppressedFormIntents,
                boostedFormIntents = acc.boostedFormIntents + next.boostedFormIntents,
                reasons = acc.reasons + next.reasons,
            )
        }

    // ------------------------------------------------------------------ //
    //  Pass 1: form intent selection (Noisy-OR fusion + log-odds prior)
    // ------------------------------------------------------------------ //

    private fun selectFormIntent(
        proposals: List<FormProposal>,
        policyResult: PolicyResult,
    ): FormIntent {
        val eligible =
            proposals.filterNot { it.formIntent in policyResult.suppressedFormIntents }
        if (eligible.isEmpty()) return FormIntent.UNKNOWN
        val scoresByIntent =
            eligible
                .groupBy { it.formIntent }
                .mapValues { (intent, ps) ->
                    val noisyOrProb = noisyOr(ps.map { it.confidence.toDouble() })
                    val lo =
                        toLogOdds(noisyOrProb) +
                                (policyResult.boostedFormIntents[intent]?.toDouble() ?: 0.0) * PRIOR_SCALE
                    sigmoid(lo)
                }
        return scoresByIntent.maxByOrNull { it.value }?.key ?: FormIntent.UNKNOWN
    }

    private fun applyPolicy(
        proposals: List<FieldProposal>,
        fieldId: AutofillId,
        policyResult: PolicyResult,
    ): List<FieldProposal> {
        val suppressed = policyResult.suppressedFieldTypes[fieldId].orEmpty()
        val boosts = policyResult.boostedFieldTypes[fieldId].orEmpty()
        return proposals
            .filterNot { it.semanticType in suppressed }
            .map { p -> p.copy(confidence = p.confidence + (boosts[p.semanticType] ?: 0f)) }
    }

    // ------------------------------------------------------------------ //
    //  Pass 2: field type selection (meta-classifier)
    // ------------------------------------------------------------------ //

    /** Maps meta-classifier class index to [SemanticType]. */
    private val META_CLASS_TO_TYPE =
        arrayOf(
            SemanticType.EMAIL_ADDRESS, // 0
            SemanticType.PASSWORD, // 1
            SemanticType.USERNAME, // 2
            SemanticType.PHONE_NUMBER, // 3
            null, // 4 = NONE
        )

    private fun selectFieldType(
        field: FieldNode,
        proposals: List<FieldProposal>,
        formIntent: FormIntent,
        context: AnalysisContext,
        options: ParseOptions,
        focusBoost: Double = 1.0,
    ): SemanticType? {
        if (shouldSuppressField(field, options)) return null
        if (proposals.isEmpty()) return null

        // 0. Structural signal override — html-type and autocomplete
        //    analyzers produce unambiguous signals (type=password,
        //    type=email, autocomplete=username, etc.) that should never
        //    be second-guessed by the meta-classifier.
        val structuralOverride = resolveStructuralOverride(proposals)
        if (structuralOverride != null) {
            val folded = foldType(structuralOverride)
            val normalized = normalizeSemanticType(folded, field, formIntent, context)
            // Suppress even structural overrides in clearly non-auth forms.
            if (FormContextPrior.isSuppressed(normalized, formIntent)) return null
            return normalized
        }

        // 1. Extract meta-features from all analyzer proposals.
        val metaFeatures =
            MetaFeatureExtractor.extract(
                field = field,
                proposals = proposals,
                formIntent = formIntent,
                context = context,
            )

        // 2. Run the meta-classifier.
        val probs = GeneratedMetaClassifier.predict(metaFeatures)

        // 2b. Focus boost: dampen NONE for focused field / cluster-mates.
        if (focusBoost < 1.0) {
            probs[NONE_CLASS_INDEX] *= focusBoost
            val sum = probs.sum()
            for (i in probs.indices) probs[i] /= sum
        }

        val bestClass = probs.indices.maxByOrNull { probs[it] } ?: return null

        // 3. Map to SemanticType; NONE → null (no autofill).
        var best = META_CLASS_TO_TYPE[bestClass]
        if (best == null) {
            // Meta-classifier predicts NONE. Check if a text-signal
            // fallback can rescue a missed credential field.
            val fallback = resolveTextSignalFallback(field, proposals, context)
            if (fallback != null) {
                val folded = foldType(fallback)
                return normalizeSemanticType(folded, field, formIntent, context)
            }
            return null
        }

        // 4. Require a minimum confidence to emit.
        if (probs[bestClass] < MIN_EMIT_PROB) return null

        // 5. Form-context hard suppressions: suppress credential types
        //    in clearly non-auth form contexts (SEARCH, COMMENT, CONTACT,
        //    SHIPPING_ADDRESS, etc.).
        if (FormContextPrior.isSuppressed(best, formIntent)) return null

        // 6. Apply field-level corrections for known misclassification
        //    patterns that cannot be expressed by the meta-classifier.
        best = normalizeSemanticType(best, field, formIntent, context)

        // 7. Re-check suppression after normalization.
        if (FormContextPrior.isSuppressed(best, formIntent)) return null

        return best
    }

    /**
     * If the `autocomplete` analyzer proposes a credential type with high
     * confidence, or the `html-type` analyzer identifies a password field,
     * return that type immediately. These are unambiguous structural signals
     * that should override the meta-classifier when it incorrectly predicts
     * NONE.
     *
     * Note: `type=email` and `type=tel` are NOT overridden because they
     * appear frequently in non-credential contexts (newsletter, contact
     * forms). Only `type=password` is unambiguous enough to warrant a
     * hard override.
     *
     * Returns `null` if no structural override applies.
     */
    private fun resolveStructuralOverride(proposals: List<FieldProposal>): SemanticType? {
        // Check autocomplete first (strongest signal, includes things
        // like autocomplete="username", "current-password", "email").
        val acBest =
            proposals
                .filter { it.analyzerId == "autocomplete" && it.confidence >= STRUCTURAL_OVERRIDE_CONF }
                .maxByOrNull { it.confidence }
        if (acBest != null && acBest.semanticType in CREDENTIAL_TYPES) {
            return acBest.semanticType
        }

        // Then check html-type, but ONLY for password fields.
        // type=email and type=tel appear in non-auth contexts too frequently.
        val htBest =
            proposals
                .filter { it.analyzerId == "html-type" && it.confidence >= STRUCTURAL_OVERRIDE_CONF }
                .maxByOrNull { it.confidence }
        if (htBest != null && htBest.semanticType in PASSWORD_TYPES) {
            return htBest.semanticType
        }

        return null
    }

    /**
     * Fallback for fields where the meta-classifier predicts NONE but
     * the text-signal analyzer has a confident credential proposal.
     *
     * This rescues fields like `type=text label="Email"` where the
     * only signal is from text analysis. The meta-classifier tends to
     * be NONE-biased for text-signal-only fields.
     *
     * Type-specific guards to minimize false positives:
     * - **EMAIL**: Requires visible label with email keyword OR explicit
     *   email name/id pattern (word-boundary match). Name/id-only with
     *   broad substring match is too noisy.
     * - **PHONE**: Requires visible label with phone keyword.
     * - **USERNAME**: Uses [hasStrongUsernameEvidence] — explicit
     *   "username"/"userid" keywords or login-related name/id pattern.
     * - **PASSWORD / OTP**: No fallback — structural override already
     *   handles `type=password` and `autocomplete=one-time-code`. Text-only
     *   password signals cause too many FPs on informational fields.
     * - Suppressed if a competing non-credential text-signal is at least
     *   as confident as the credential signal.
     */
    private fun resolveTextSignalFallback(
        field: FieldNode,
        proposals: List<FieldProposal>,
        context: AnalysisContext,
    ): SemanticType? {
        val textSignalProposals = proposals.filter { it.analyzerId == "text-signal" }

        val bestCredential =
            textSignalProposals
                .filter { it.semanticType in FALLBACK_CREDENTIAL_TYPES }
                .maxByOrNull { it.confidence }
                ?: return null

        // Don't fallback if there's a competing non-credential text-signal
        // with confidence at least as high (e.g. SEARCH 0.98 vs EMAIL 0.9).
        val bestNonCredential =
            textSignalProposals
                .filter { it.semanticType !in FALLBACK_CREDENTIAL_TYPES }
                .maxByOrNull { it.confidence }
        if (bestNonCredential != null &&
            bestNonCredential.confidence >= bestCredential.confidence
        ) {
            return null
        }

        return when (bestCredential.semanticType) {
            // EMAIL: require visible label OR explicit email name/id.
            SemanticType.EMAIL_ADDRESS -> {
                if (bestCredential.confidence >= TEXT_SIGNAL_FALLBACK_CONF) {
                    val visibleBlob = visibleLabelBlob(field)
                    val visibleMatch = KeywordMatcher.match(visibleBlob)
                    val hasVisibleEmail =
                        visibleMatch has KeywordTag.EMAIL
                    val hasExplicitNameIdEmail =
                        EXPLICIT_EMAIL_NAME_ID_REGEX.containsMatchIn(
                            context.nameIdBlobs[field.id] ?: nameIdBlob(field),
                        )
                    if (hasVisibleEmail || hasExplicitNameIdEmail) {
                        SemanticType.EMAIL_ADDRESS
                    } else {
                        null
                    }
                } else {
                    null
                }
            }

            // PHONE: require visible label with phone keyword.
            SemanticType.PHONE_NUMBER -> {
                if (bestCredential.confidence >= TEXT_SIGNAL_FALLBACK_CONF) {
                    val visibleBlob = visibleLabelBlob(field)
                    val visibleMatch = KeywordMatcher.match(visibleBlob)
                    if (visibleMatch has KeywordTag.PHONE) {
                        SemanticType.PHONE_NUMBER
                    } else {
                        null
                    }
                } else {
                    null
                }
            }

            // USERNAME: lower threshold with strong evidence.
            SemanticType.USERNAME -> {
                if (bestCredential.confidence >= USERNAME_FALLBACK_CONF &&
                    hasStrongUsernameEvidence(field, context)
                ) {
                    SemanticType.USERNAME
                } else {
                    null
                }
            }

            // PASSWORD: require password token in name/id blob (not just
            // label text). This prevents false positives from informational
            // mentions like "password recovery" while still catching
            // type=text fields whose developer named them "password".
            SemanticType.PASSWORD -> {
                if (bestCredential.confidence >= TEXT_SIGNAL_FALLBACK_CONF) {
                    val nidBlob = context.nameIdBlobs[field.id] ?: nameIdBlob(field)
                    if (PASSWORD_TOKEN_REGEX.containsMatchIn(nidBlob)) {
                        SemanticType.PASSWORD
                    } else {
                        null
                    }
                } else {
                    null
                }
            }

            // OTP: require OTP keyword in name/id blob.
            SemanticType.OTP -> {
                if (bestCredential.confidence >= TEXT_SIGNAL_FALLBACK_CONF) {
                    val nm =
                        context.nameIdBlobMatches[field.id]
                            ?: KeywordMatcher.match(context.nameIdBlobs[field.id] ?: nameIdBlob(field))
                    if (nm has KeywordTag.OTP) {
                        SemanticType.OTP
                    } else {
                        null
                    }
                } else {
                    null
                }
            }

            else -> {
                null
            }
        }
    }

    private fun visibleLabelBlob(field: FieldNode): String =
        buildString {
            append(field.label.orEmpty())
            append(' ')
            append(field.attributes["placeholder"].orEmpty())
            append(' ')
            append(field.viewHint.orEmpty())
            append(' ')
            append(field.contentDescription.orEmpty())
        }.lowercase()

    /**
     * Returns `true` if the field has strong textual evidence of being
     * a username/login identifier — explicit "username"/"user id" keywords
     * or a login-related name/id pattern.
     *
     * Weaker signals like "account", "customer", "client" are not
     * considered strong enough to override the meta-classifier.
     *
     * Uses multilingual username keywords from [USERNAME_KEYWORDS] but
     * filters to the "strong" subset (explicit username/userid terms
     * rather than generic "account"/"customer"/"client").
     */
    private fun hasStrongUsernameEvidence(
        field: FieldNode,
        context: AnalysisContext,
    ): Boolean {
        val m =
            context.fieldBlobMatches[field.id]
                ?: KeywordMatcher.match(context.fieldBlobs[field.id] ?: fieldBlob(field))
        if (m has KeywordTag.STRONG_USERNAME) return true
        val nidBlob = context.nameIdBlobs[field.id] ?: nameIdBlob(field)
        if (LOGIN_IDENTIFIER_REGEX.containsMatchIn(nidBlob)) return true
        return false
    }

    // ------------------------------------------------------------------ //

    /**
     * Converts a probability in (0, 1) to log-odds: log(p / (1-p)).
     * Clamps to [MIN_CONFIDENCE, MAX_CONFIDENCE] to avoid infinities.
     */
    private fun toLogOdds(p: Double): Double {
        val clamped = p.coerceIn(MIN_CONFIDENCE, MAX_CONFIDENCE)
        return ln(clamped / (1.0 - clamped))
    }

    /**
     * Converts log-odds back to a probability via the sigmoid function:
     * 1 / (1 + exp(-lo)).
     */
    private fun sigmoid(lo: Double): Double = 1.0 / (1.0 + exp(-lo))

    // ------------------------------------------------------------------ //
    //  Noisy-OR fusion
    // ------------------------------------------------------------------ //

    /**
     * Combines a list of independent confidence values using the Noisy-OR
     * model: `P = 1 - ∏(1 - p_i)`. This gives "the probability that at
     * least one analyzer is correct", which naturally rewards corroborating
     * evidence without the log-sum penalty that punishes well-supported
     * types for having many moderate-confidence votes.
     */
    private fun noisyOr(confidences: List<Double>): Double =
        1.0 -
                confidences.fold(1.0) { acc, p ->
                    acc * (1.0 - p.coerceIn(MIN_CONFIDENCE, MAX_CONFIDENCE))
                }

    // ------------------------------------------------------------------ //
    //  Type folding: collapse "new" variants into their base type
    // ------------------------------------------------------------------ //

    private fun foldType(type: SemanticType): SemanticType =
        when (type) {
            SemanticType.NEW_PASSWORD -> SemanticType.PASSWORD
            SemanticType.NEW_USERNAME -> SemanticType.USERNAME
            else -> type
        }

    // ------------------------------------------------------------------ //
    //  Field-level type corrections
    //
    //  These handle cases where the Bayesian fusion picks the wrong winner
    //  due to mixed signals (e.g. "Email password" in a placeholder) that
    //  cannot be resolved by form-context alone.
    // ------------------------------------------------------------------ //

    private fun normalizeSemanticType(
        semanticType: SemanticType,
        field: FieldNode,
        formIntent: FormIntent,
        context: AnalysisContext,
    ): SemanticType =
        when (semanticType) {
            SemanticType.PASSWORD -> {
                if (looksLikeResetEmailIdentifier(field, context) ||
                    isWeakPasswordHintForEmailIdentifier(field, context)
                ) {
                    SemanticType.EMAIL_ADDRESS
                } else {
                    SemanticType.PASSWORD
                }
            }

            SemanticType.PHONE_NUMBER -> {
                if (formIntent == FormIntent.LOGIN && looksLikeTelLoginIdentifier(field, context)) {
                    SemanticType.USERNAME
                } else if (looksLikeMixedEmailIdentifier(field, context)) {
                    SemanticType.EMAIL_ADDRESS
                } else if (formIntent == FormIntent.LOGIN &&
                    looksLikeUsernameIdentifier(field, context) &&
                    !looksLikePhoneIdentifier(field, context)
                ) {
                    SemanticType.USERNAME
                } else {
                    SemanticType.PHONE_NUMBER
                }
            }

            SemanticType.EMAIL_ADDRESS -> {
                if (formIntent == FormIntent.LOGIN &&
                    looksLikeUsernameIdentifier(field, context) &&
                    !looksLikeEmailIdentifier(field, context)
                ) {
                    SemanticType.USERNAME
                } else {
                    SemanticType.EMAIL_ADDRESS
                }
            }

            else -> {
                semanticType
            }
        }

    // ------------------------------------------------------------------ //
    //  Field-level signal helpers (used by normalizeSemanticType only)
    // ------------------------------------------------------------------ //

    private fun looksLikeResetEmailIdentifier(
        field: FieldNode,
        context: AnalysisContext,
    ): Boolean {
        if (field.effectiveType == "email") return true
        val m =
            context.fieldBlobMatches[field.id]
                ?: KeywordMatcher.match(context.fieldBlobs[field.id] ?: fieldBlob(field))
        val hasEmail = m has KeywordTag.EMAIL
        val hasReset = m has KeywordTag.RESET
        return hasEmail && hasReset
    }

    private fun isWeakPasswordHintForEmailIdentifier(
        field: FieldNode,
        context: AnalysisContext,
    ): Boolean {
        if (field.effectiveType == "password") return false
        val acBlob = context.autocompleteBlobs[field.id] ?: autocompleteBlob(field)
        if ("current-password" in acBlob ||
            "new-password" in acBlob ||
            "password" in acBlob
        ) {
            return false
        }
        val nidBlob = context.nameIdBlobs[field.id] ?: nameIdBlob(field)
        val hasEmailIdentity = EXPLICIT_EMAIL_NAME_ID_REGEX.containsMatchIn(nidBlob)
        if (!hasEmailIdentity) return false
        return !PASSWORD_TOKEN_REGEX.containsMatchIn(nidBlob)
    }

    private fun looksLikeUsernameIdentifier(
        field: FieldNode,
        context: AnalysisContext,
    ): Boolean {
        val m =
            context.fieldBlobMatches[field.id]
                ?: KeywordMatcher.match(context.fieldBlobs[field.id] ?: fieldBlob(field))
        return m has KeywordTag.USERNAME
    }

    private fun looksLikeEmailIdentifier(
        field: FieldNode,
        context: AnalysisContext,
    ): Boolean {
        if (field.effectiveType == "email") return true
        val acBlob = context.autocompleteBlobs[field.id] ?: autocompleteBlob(field)
        val acMatch = KeywordMatcher.match(acBlob)
        if (acMatch has KeywordTag.EMAIL) return true
        val visibleBlob = visibleLabelBlob(field)
        val visibleMatch = KeywordMatcher.match(visibleBlob)
        if (visibleMatch has KeywordTag.EMAIL) return true
        val nidBlob = context.nameIdBlobs[field.id] ?: nameIdBlob(field)
        val hasExplicitNameIdEmail = EXPLICIT_EMAIL_NAME_ID_REGEX.containsMatchIn(nidBlob)
        if (!hasExplicitNameIdEmail) return false
        return !looksLikeUsernameIdentifier(field, context)
    }

    private fun looksLikePhoneIdentifier(
        field: FieldNode,
        context: AnalysisContext,
    ): Boolean {
        val acBlob = context.autocompleteBlobs[field.id] ?: autocompleteBlob(field)
        if ("tel" in acBlob || "phone" in acBlob) return true
        val m =
            context.fieldBlobMatches[field.id]
                ?: KeywordMatcher.match(context.fieldBlobs[field.id] ?: fieldBlob(field))
        val htmlType = field.htmlType
        val hasExplicitPhoneWords = m has KeywordTag.PHONE
        if (htmlType == "tel" && hasExplicitPhoneWords) return true
        if (htmlType == "tel") return false
        return hasExplicitPhoneWords
    }

    private fun looksLikeTelLoginIdentifier(
        field: FieldNode,
        context: AnalysisContext,
    ): Boolean {
        val htmlType = field.htmlType ?: return false
        if (htmlType != "tel") return false
        val m =
            context.fieldBlobMatches[field.id]
                ?: KeywordMatcher.match(context.fieldBlobs[field.id] ?: fieldBlob(field))
        val blob = context.fieldBlobs[field.id] ?: fieldBlob(field)
        val hasIdentifierWords =
            (m has KeywordTag.USERNAME) ||
                    containsAny(
                        blob,
                        "identifier",
                        "login",
                    )
        val hasExplicitPhoneWords = m has KeywordTag.PHONE
        return hasIdentifierWords && !hasExplicitPhoneWords
    }

    private fun looksLikeMixedEmailIdentifier(
        field: FieldNode,
        context: AnalysisContext,
    ): Boolean {
        val m =
            context.fieldBlobMatches[field.id]
                ?: KeywordMatcher.match(context.fieldBlobs[field.id] ?: fieldBlob(field))
        val hasEmail = m has KeywordTag.EMAIL
        val hasAlternativeIdentifier =
            (m has KeywordTag.USERNAME) ||
                    (m has KeywordTag.PHONE)
        return hasEmail && hasAlternativeIdentifier
    }

    // ------------------------------------------------------------------ //
    //  Field suppression (HTML control types, autocomplete=off)
    // ------------------------------------------------------------------ //

    private fun shouldSuppressField(
        field: FieldNode,
        options: ParseOptions,
    ): Boolean {
        val htmlType = field.htmlType
        val isSelectLike =
            field.htmlTag == "select" ||
                    (field.isNative && field.className?.endsWith("Spinner") == true)
        if (isSelectLike || htmlType in SUPPRESSED_HTML_TYPES) return true
        if (options.respectAutofillOff) {
            // Respect the platform's importantForAutofill signal: when set
            // to NO the app has explicitly opted this field out of autofill.
            if (field.importantForAutofill == View.IMPORTANT_FOR_AUTOFILL_NO) {
                return true
            }
            val autocomplete = field.attributes["autocomplete"]?.trim()?.lowercase()
            if (autocomplete == "off" ||
                field.autofillHints.any { it.equals("off", ignoreCase = true) }
            ) {
                return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------ //
    //  Constants
    // ------------------------------------------------------------------ //

    private companion object {
        /** Floor for confidence values in Noisy-OR and log-odds, avoids degenerate 0. */
        const val MIN_CONFIDENCE = 0.01

        /** Ceiling for confidence values; prevents a single
         *  confidence=1.0 from collapsing the product to 0 or giving +∞ log-odds. */
        const val MAX_CONFIDENCE = 0.99

        /**
         * Scaling factor for [FormContextPrior] values in the log-odds
         * form intent selection. The prior table's raw values are multiplied
         * by this factor to keep the prior as a meaningful but not dominant
         * signal for intent classification.
         */
        const val PRIOR_SCALE = 0.10

        /**
         * Minimum softmax probability required to emit a field type.
         * With the meta-classifier, NONE is already a class so argmax
         * handles uncertain fields. Set to 0.0 because the classifier's
         * own confidence is reliable enough.
         */
        const val MIN_EMIT_PROB = 0.0

        /**
         * Minimum confidence from a structural analyzer (html-type,
         * autocomplete) to trigger a hard override of the meta-classifier.
         * These analyzers produce unambiguous signals that should not be
         * overridden by a learned model.
         */
        const val STRUCTURAL_OVERRIDE_CONF = 0.85f

        /** HTML types that provide strong structural evidence of field purpose. */
        val STRUCTURAL_HTML_TYPES = setOf("password", "email", "tel")

        /** Credential types eligible for structural override. */
        val CREDENTIAL_TYPES =
            setOf(
                SemanticType.EMAIL_ADDRESS,
                SemanticType.PASSWORD,
                SemanticType.NEW_PASSWORD,
                SemanticType.USERNAME,
                SemanticType.NEW_USERNAME,
                SemanticType.PHONE_NUMBER,
                SemanticType.OTP,
            )

        /** Password types eligible for html-type structural override.
         *  Only password is unambiguous enough from html type alone. */
        val PASSWORD_TYPES =
            setOf(
                SemanticType.PASSWORD,
                SemanticType.NEW_PASSWORD,
            )

        /**
         * Credential types eligible for the text-signal fallback when the
         * meta-classifier predicts NONE.
         */
        val FALLBACK_CREDENTIAL_TYPES =
            setOf(
                SemanticType.EMAIL_ADDRESS,
                SemanticType.PASSWORD,
                SemanticType.USERNAME,
                SemanticType.PHONE_NUMBER,
                SemanticType.OTP,
            )

        /**
         * Minimum text-signal confidence for the NONE-fallback to trigger
         * (EMAIL, PASSWORD, PHONE, OTP). Set just above the text-signal
         * analyzer's typical credential thresholds so that only deliberate
         * keyword matches qualify.
         */
        const val TEXT_SIGNAL_FALLBACK_CONF = 0.85f

        /**
         * Lower fallback threshold for USERNAME, which the text-signal
         * analyzer assigns at 0.80–0.82. Requires additional corroboration
         * via [hasStrongUsernameEvidence].
         */
        const val USERNAME_FALLBACK_CONF = 0.78f

        val SUPPRESSED_HTML_TYPES =
            setOf("checkbox", "radio", "hidden", "submit", "button", "image")

        /** Index of the NONE class in the meta-classifier output. */
        const val NONE_CLASS_INDEX = 4

        /**
         * NONE-probability dampening factor for the focused field.
         * Multiplied against the NONE class probability before renormalizing,
         * making the field less likely to be classified as "nothing."
         */
        const val FOCUS_BOOST_FIELD = 0.6

        /**
         * NONE-probability dampening factor for fields in the same cluster
         * as the focused field. A gentler nudge than [FOCUS_BOOST_FIELD] —
         * only rescues fields already on the borderline.
         */
        const val FOCUS_BOOST_CLUSTER = 0.8
    }
}
