package com.artemchep.keyguard.feature.home.vault.search.query.compiler

import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.search.engine.SearchTokenizer
import com.artemchep.keyguard.feature.home.vault.search.engine.SearchTokenizerProfile
import com.artemchep.keyguard.feature.home.vault.search.engine.profile
import com.artemchep.keyguard.feature.home.vault.search.query.VaultSearchQualifierCatalog
import com.artemchep.keyguard.feature.home.vault.search.query.VaultSearchQualifierDefinition
import com.artemchep.keyguard.feature.home.vault.search.query.findVaultSearchQualifierDefinition
import com.artemchep.keyguard.feature.home.vault.search.query.model.ClauseNode
import com.artemchep.keyguard.feature.home.vault.search.query.model.NegatedNode
import com.artemchep.keyguard.feature.home.vault.search.query.model.ParsedQuery
import com.artemchep.keyguard.feature.home.vault.search.query.model.QuotedValueNode
import com.artemchep.keyguard.feature.home.vault.search.query.model.QualifiedTermNode
import com.artemchep.keyguard.feature.home.vault.search.query.model.QueryValueNode
import com.artemchep.keyguard.feature.home.vault.search.query.model.SourceSpan
import com.artemchep.keyguard.feature.home.vault.search.query.model.TextValueNode
import com.artemchep.keyguard.feature.home.vault.search.query.model.TextTermNode

class DefaultVaultSearchQueryCompiler(
    private val tokenizer: SearchTokenizer,
) : VaultSearchQueryCompiler {
    override fun compile(
        query: ParsedQuery,
        searchBy: VaultRoute.Args.SearchBy,
        qualifierCatalog: VaultSearchQualifierCatalog,
    ): CompiledQueryPlan {
        val positive = mutableListOf<CompiledQueryClause>()
        val negative = mutableListOf<CompiledQueryClause>()

        query.clauses.forEach { rawClause ->
            val (isNegative, clause) =
                when (rawClause) {
                    is NegatedNode -> true to rawClause.clause
                    else -> false to rawClause
                }
            val compiled =
                compileClause(
                    clause = clause,
                    searchBy = searchBy,
                    qualifierCatalog = qualifierCatalog,
                ) ?: return@forEach
            if (isNegative) {
                negative += compiled
            } else {
                positive += compiled
            }
        }

        return CompiledQueryPlan(
            rawQuery = query.source,
            parsedQuery = query,
            positiveClauses = positive,
            negativeClauses = negative,
        )
    }

    private fun compileClause(
        clause: ClauseNode,
        searchBy: VaultRoute.Args.SearchBy,
        qualifierCatalog: VaultSearchQualifierCatalog,
    ): CompiledQueryClause? =
        when (clause) {
            is TextTermNode -> {
                compileTextTerm(
                    raw = clause.raw,
                    value = clause.value,
                    searchBy = searchBy,
                )
            }

            is QualifiedTermNode -> {
                compileQualifiedTerm(
                    clause = clause,
                    searchBy = searchBy,
                    qualifierCatalog = qualifierCatalog,
                )
            }

            else -> {
                null
            }
        }

    private fun compileTextTerm(
        raw: String,
        value: QueryValueNode,
        searchBy: VaultRoute.Args.SearchBy,
    ): CompiledQueryClause? =
        when (searchBy) {
            VaultRoute.Args.SearchBy.ALL -> {
                compileHotTextClause(
                    raw = raw,
                    value = value,
                    fields =
                        setOf(
                            VaultTextField.Title,
                            VaultTextField.Url,
                            VaultTextField.Host,
                            VaultTextField.PasskeyRpId,
                            VaultTextField.Username,
                            VaultTextField.Email,
                            VaultTextField.IdentityName,
                            VaultTextField.Phone,
                            VaultTextField.CardholderName,
                            VaultTextField.PasskeyDisplayName,
                            VaultTextField.FieldName,
                            VaultTextField.AttachmentName,
                        ),
                )
            }

            VaultRoute.Args.SearchBy.PASSWORD -> {
                compileColdTextClause(
                    raw = raw,
                    value = value,
                    field = VaultTextField.Password,
                )
            }
        }

    private fun compileQualifiedTerm(
        clause: QualifiedTermNode,
        searchBy: VaultRoute.Args.SearchBy,
        qualifierCatalog: VaultSearchQualifierCatalog,
    ): CompiledQueryClause? {
        val value =
            clause.value
                ?: return null

        return when (
            val definition =
                findVaultSearchQualifierDefinition(
                    qualifier = clause.qualifier,
                    catalog = qualifierCatalog,
                )
        ) {
            is VaultSearchQualifierDefinition.HotText -> {
                compileHotTextClause(
                    raw = clause.raw,
                    value = value,
                    fields = definition.fields,
                )
            }

            is VaultSearchQualifierDefinition.ColdText -> {
                compileColdTextClause(
                    raw = clause.raw,
                    value = value,
                    field = definition.field,
                )
            }

            is VaultSearchQualifierDefinition.Facet -> {
                compileFacetClause(
                    raw = clause.raw,
                    value = value,
                    field = definition.field,
                )
            }

            is VaultSearchQualifierDefinition.Boolean -> {
                compileBooleanClause(
                    raw = clause.raw,
                    value = value,
                    field = definition.field,
                )
            }

            null -> {
                compileTextTerm(
                    raw = clause.raw,
                    value = fallbackTextValue(clause),
                    searchBy = searchBy,
                )
            }
        }
    }

    private fun fallbackTextValue(
        clause: QualifiedTermNode,
    ): QueryValueNode {
        val value = clause.value ?: return TextValueNode(
            value = clause.qualifier,
            span = clause.qualifierSpan,
        )
        val span = SourceSpan(
            start = clause.qualifierSpan.start,
            end = value.span.end,
        )
        val text = "${clause.qualifier}:${value.value}"
        return if (value.quoted) {
            QuotedValueNode(
                value = text,
                span = span,
            )
        } else {
            TextValueNode(
                value = text,
                span = span,
            )
        }
    }

    private fun compileHotTextClause(
        raw: String,
        value: QueryValueNode,
        fields: Set<VaultTextField>,
    ): CompiledHotTextClause? {
        val tokenizations =
            fields
                .map(VaultTextField::profile)
                .distinct()
                .associateWith { profile ->
                    tokenizer.tokenize(
                        value = value.value,
                        profile = profile,
                    )
                }
        if (tokenizations.values.all { it.terms.isEmpty() }) {
            return null
        }
        return CompiledHotTextClause(
            fields = fields,
            tokenizations = tokenizations,
            rawPhrase = value.value.takeIf { value.quoted },
            raw = raw,
        )
    }

    private fun compileColdTextClause(
        raw: String,
        value: QueryValueNode,
        field: VaultTextField,
    ): CompiledColdTextClause? {
        val profile =
            when (field) {
                VaultTextField.Ssh,
                VaultTextField.Password,
                VaultTextField.CardNumber,
                -> SearchTokenizerProfile.SENSITIVE

                else -> SearchTokenizerProfile.TEXT
            }
        val tokenization =
            tokenizer.tokenize(
                value = value.value,
                profile = profile,
            )
        if (tokenization.terms.isEmpty()) {
            return null
        }
        return CompiledColdTextClause(
            field = field,
            tokenization = tokenization,
            rawPhrase = value.value.takeIf { value.quoted },
            raw = raw,
        )
    }

    private fun compileFacetClause(
        raw: String,
        value: QueryValueNode,
        field: VaultFacetField,
    ): CompiledFacetClause? {
        val normalized =
            when (field) {
                VaultFacetField.Type -> {
                    normalizeType(value.value)
                }

                else -> {
                    tokenizer.normalize(
                        value = value.value,
                        profile = field.profile(),
                    )
                }
            }
        if (normalized.isBlank()) {
            return null
        }
        return CompiledFacetClause(
            field = field,
            values = setOf(normalized),
            raw = raw,
        )
    }

    private fun compileBooleanClause(
        raw: String,
        value: QueryValueNode,
        field: VaultBooleanField,
    ): CompiledBooleanClause? {
        val normalized = value.value.trim().lowercase()
        val booleanValue =
            when (normalized) {
                "true" -> true
                "false" -> false
                else -> return null
            }
        return CompiledBooleanClause(
            field = field,
            value = booleanValue,
            raw = raw,
        )
    }

    private fun normalizeType(value: String): String =
        value
            .trim()
            .lowercase()
            .replace("_", "")
            .replace("-", "")
            .replace(" ", "")
}
