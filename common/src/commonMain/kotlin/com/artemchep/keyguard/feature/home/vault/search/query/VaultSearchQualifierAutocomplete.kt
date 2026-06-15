package com.artemchep.keyguard.feature.home.vault.search.query

import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultBooleanField
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultFacetField
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultQueryClauseSemanticKind
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultTextField
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.StringResource

internal data class VaultSearchQualifierMetadata(
    val canonicalName: String,
    val aliases: Set<String>,
    val semanticKind: VaultQueryClauseSemanticKind,
)

class VaultSearchQualifierCatalog internal constructor(
    internal val definitions: List<VaultSearchQualifierDefinition>,
) {
    private val definitionsByAlias: Map<String, VaultSearchQualifierDefinition> =
        definitions
            .flatMap { definition ->
                definition.metadata.aliases.map { alias ->
                    alias to definition
                }
            }.toMap()

    internal val metadata: List<VaultSearchQualifierMetadata>
        get() = definitions.map(VaultSearchQualifierDefinition::metadata)

    internal fun findDefinition(
        qualifier: String,
    ): VaultSearchQualifierDefinition? = definitionsByAlias[qualifier.lowercase()]
}

internal data class VaultSearchQualifierSuggestion(
    val metadata: VaultSearchQualifierMetadata,
) {
    val text: String
        get() = "${metadata.canonicalName}:"
}

data class VaultSearchQualifierApplyResult(
    val text: String,
    val cursor: Int,
)

internal sealed interface VaultSearchQualifierDefinition {
    val metadata: VaultSearchQualifierMetadata

    data class HotText(
        override val metadata: VaultSearchQualifierMetadata,
        val fields: Set<VaultTextField>,
    ) : VaultSearchQualifierDefinition

    data class ColdText(
        override val metadata: VaultSearchQualifierMetadata,
        val field: VaultTextField,
    ) : VaultSearchQualifierDefinition

    data class Facet(
        override val metadata: VaultSearchQualifierMetadata,
        val field: VaultFacetField,
    ) : VaultSearchQualifierDefinition

    data class Boolean(
        override val metadata: VaultSearchQualifierMetadata,
        val field: VaultBooleanField,
    ) : VaultSearchQualifierDefinition
}

internal fun findVaultSearchQualifierDefinition(
    qualifier: String,
    catalog: VaultSearchQualifierCatalog = defaultVaultSearchQualifierCatalog,
): VaultSearchQualifierDefinition? = catalog.findDefinition(qualifier)

internal fun bestVaultSearchQualifierSuggestion(
    query: String,
    catalog: VaultSearchQualifierCatalog = defaultVaultSearchQualifierCatalog,
): VaultSearchQualifierSuggestion? {
    val fragment =
        activeVaultSearchQualifierFragment(query)
            ?: return null
    val normalizedFragment = fragment.text.lowercase()
    val match =
        catalog.definitions
            .withIndex()
            .mapNotNull { (index, definition) ->
                val score =
                    definition.metadata.score(normalizedFragment)
                        ?: return@mapNotNull null
                QualifierMatch(
                    definition = definition,
                    order = index,
                    score = score,
                )
            }.maxWithOrNull(
                compareBy<QualifierMatch>(
                    QualifierMatch::score,
                    { -it.order },
                ),
            )
            ?: return null
    return VaultSearchQualifierSuggestion(match.definition.metadata)
}

internal fun applyVaultSearchQualifierSuggestion(
    query: String,
    suggestion: VaultSearchQualifierSuggestion,
): VaultSearchQualifierApplyResult {
    val fragment =
        activeVaultSearchQualifierFragment(query)
            ?: return VaultSearchQualifierApplyResult(
                text = query,
                cursor = query.length,
            )
    val prefix = if (fragment.negated) "-" else ""
    val text = buildString(
        capacity = query.length + suggestion.text.length,
    ) {
        append(query.substring(0, fragment.start))
        append(prefix)
        append(suggestion.text)
    }
    return VaultSearchQualifierApplyResult(
        text = text,
        cursor = text.length,
    )
}

private data class QualifierMatch(
    val definition: VaultSearchQualifierDefinition,
    val order: Int,
    val score: Int,
)

private data class ActiveQualifierFragment(
    val start: Int,
    val text: String,
    val negated: Boolean,
)

private fun activeVaultSearchQualifierFragment(query: String): ActiveQualifierFragment? {
    if (query.isEmpty() || query.last().isWhitespace()) {
        return null
    }

    val start =
        query
            .indexOfLast(Char::isWhitespace)
            .let { whitespaceIndex -> whitespaceIndex + 1 }
    val rawFragment = query.substring(start)
    if (rawFragment.contains(':')) {
        return null
    }

    val negated = rawFragment.startsWith("-")
    val normalized = rawFragment.removePrefix("-")
    if (normalized.length < 3) {
        return null
    }

    return ActiveQualifierFragment(
        start = start,
        text = normalized,
        negated = negated,
    )
}

private fun VaultSearchQualifierMetadata.score(
    fragment: String,
): Int? = aliases
    .asSequence()
    .mapNotNull { alias ->
        if (!alias.startsWith(fragment)) {
            return@mapNotNull null
        }

        val exactMatchBonus =
            if (alias.length == fragment.length) {
                200
            } else {
                0
            }
        val canonicalBonus =
            if (alias == canonicalName) {
                100
            } else {
                0
            }
        val lengthPenalty = alias.length - fragment.length
        1_000 + exactMatchBonus + canonicalBonus - lengthPenalty
    }
    .maxOrNull()

private fun qualifierMetadata(
    canonicalName: String,
    aliases: Collection<String>,
    localizedAliases: Set<String>,
    semanticKind: VaultQueryClauseSemanticKind,
): VaultSearchQualifierMetadata {
    val normalizedAliases = buildSet {
        add(canonicalName.lowercase())
        aliases.forEach { alias ->
            add(alias.lowercase())
        }
        localizedAliases.forEach { alias ->
            add(alias.lowercase())
        }
    }
    return VaultSearchQualifierMetadata(
        canonicalName = canonicalName,
        aliases = normalizedAliases,
        semanticKind = semanticKind,
    )
}

private data class VaultSearchQualifierDefinitionSpec(
    val canonicalName: String,
    val aliases: Set<String>,
    val semanticKind: VaultQueryClauseSemanticKind,
    val textFields: Set<VaultTextField>? = null,
    val coldTextField: VaultTextField? = null,
    val facetField: VaultFacetField? = null,
    val booleanField: VaultBooleanField? = null,
    val localizationKey: StringResource? = null,
)

internal fun buildVaultSearchQualifierCatalog(
    localizedAliasesByCanonicalName: Map<String, Set<String>> = emptyMap(),
): VaultSearchQualifierCatalog = kotlin.run {
    val definitions = vaultSearchQualifierDefinitionSpecs.map { spec ->
        val localizedAliases = localizedAliasesByCanonicalName[spec.canonicalName].orEmpty()
        val metadata = qualifierMetadata(
            canonicalName = spec.canonicalName,
            aliases = spec.aliases,
            localizedAliases = localizedAliases,
            semanticKind = spec.semanticKind,
        )
        when {
            spec.textFields != null -> VaultSearchQualifierDefinition.HotText(
                metadata = metadata,
                fields = spec.textFields,
            )

            spec.coldTextField != null -> VaultSearchQualifierDefinition.ColdText(
                metadata = metadata,
                field = spec.coldTextField,
            )

            spec.facetField != null -> VaultSearchQualifierDefinition.Facet(
                metadata = metadata,
                field = spec.facetField,
            )

            spec.booleanField != null -> VaultSearchQualifierDefinition.Boolean(
                metadata = metadata,
                field = spec.booleanField,
            )

            else -> error("Unsupported qualifier spec '${spec.canonicalName}'.")
        }
    }
    VaultSearchQualifierCatalog(
        definitions = definitions,
    )
}

private val vaultSearchQualifierDefinitionSpecs =
    listOf(
        VaultSearchQualifierDefinitionSpec(
            canonicalName = "title",
            aliases = setOf("name"),
            semanticKind = VaultQueryClauseSemanticKind.Text,
            textFields = setOf(VaultTextField.Title),
        ),
        VaultSearchQualifierDefinitionSpec(
            canonicalName = "username",
            aliases = emptySet(),
            semanticKind = VaultQueryClauseSemanticKind.Text,
            textFields = setOf(VaultTextField.Username),
        ),
        VaultSearchQualifierDefinitionSpec(
            canonicalName = "email",
            aliases = emptySet(),
            semanticKind = VaultQueryClauseSemanticKind.Text,
            textFields = setOf(VaultTextField.Email),
        ),
        VaultSearchQualifierDefinitionSpec(
            canonicalName = "url",
            aliases = emptySet(),
            semanticKind = VaultQueryClauseSemanticKind.Text,
            textFields = setOf(VaultTextField.Url),
        ),
        VaultSearchQualifierDefinitionSpec(
            canonicalName = "domain",
            aliases = setOf("host"),
            semanticKind = VaultQueryClauseSemanticKind.Text,
            textFields =
                setOf(
                    VaultTextField.Host,
                    VaultTextField.PasskeyRpId,
                ),
        ),
        VaultSearchQualifierDefinitionSpec(
            canonicalName = "note",
            aliases = emptySet(),
            semanticKind = VaultQueryClauseSemanticKind.Text,
            coldTextField = VaultTextField.Note,
        ),
        VaultSearchQualifierDefinitionSpec(
            canonicalName = "field",
            aliases = emptySet(),
            semanticKind = VaultQueryClauseSemanticKind.Text,
            coldTextField = VaultTextField.Field,
        ),
        VaultSearchQualifierDefinitionSpec(
            canonicalName = "attachment",
            aliases = emptySet(),
            semanticKind = VaultQueryClauseSemanticKind.Text,
            textFields = setOf(VaultTextField.AttachmentName),
        ),
        VaultSearchQualifierDefinitionSpec(
            canonicalName = "passkey",
            aliases = emptySet(),
            semanticKind = VaultQueryClauseSemanticKind.Text,
            textFields =
                setOf(
                    VaultTextField.PasskeyRpId,
                    VaultTextField.PasskeyDisplayName,
                ),
        ),
        VaultSearchQualifierDefinitionSpec(
            canonicalName = "ssh",
            aliases = emptySet(),
            semanticKind = VaultQueryClauseSemanticKind.Text,
            coldTextField = VaultTextField.Ssh,
        ),
        VaultSearchQualifierDefinitionSpec(
            canonicalName = "password",
            aliases = emptySet(),
            semanticKind = VaultQueryClauseSemanticKind.Text,
            coldTextField = VaultTextField.Password,
        ),
        VaultSearchQualifierDefinitionSpec(
            canonicalName = "card-number",
            aliases = emptySet(),
            semanticKind = VaultQueryClauseSemanticKind.Text,
            coldTextField = VaultTextField.CardNumber,
        ),
        VaultSearchQualifierDefinitionSpec(
            canonicalName = "card-brand",
            aliases = emptySet(),
            semanticKind = VaultQueryClauseSemanticKind.Text,
            textFields = setOf(VaultTextField.CardBrand),
        ),
        VaultSearchQualifierDefinitionSpec(
            canonicalName = "account",
            aliases = emptySet(),
            semanticKind = VaultQueryClauseSemanticKind.Facet,
            facetField = VaultFacetField.Account,
            localizationKey = Res.string.account,
        ),
        VaultSearchQualifierDefinitionSpec(
            canonicalName = "folder",
            aliases = emptySet(),
            semanticKind = VaultQueryClauseSemanticKind.Facet,
            facetField = VaultFacetField.Folder,
            localizationKey = Res.string.folder,
        ),
        VaultSearchQualifierDefinitionSpec(
            canonicalName = "tag",
            aliases = emptySet(),
            semanticKind = VaultQueryClauseSemanticKind.Facet,
            facetField = VaultFacetField.Tag,
            localizationKey = Res.string.tag,
        ),
        VaultSearchQualifierDefinitionSpec(
            canonicalName = "organization",
            aliases = emptySet(),
            semanticKind = VaultQueryClauseSemanticKind.Facet,
            facetField = VaultFacetField.Organization,
            localizationKey = Res.string.organization,
        ),
        VaultSearchQualifierDefinitionSpec(
            canonicalName = "collection",
            aliases = emptySet(),
            semanticKind = VaultQueryClauseSemanticKind.Facet,
            facetField = VaultFacetField.Collection,
            localizationKey = Res.string.collection,
        ),
    )

internal val localizableVaultSearchQualifierKeys: Map<String, StringResource> =
    vaultSearchQualifierDefinitionSpecs
        .mapNotNull { spec ->
            spec.localizationKey?.let { key ->
                spec.canonicalName to key
            }
        }.toMap()

internal val defaultVaultSearchQualifierCatalog: VaultSearchQualifierCatalog =
    buildVaultSearchQualifierCatalog()

internal val supportedVaultSearchQualifierMetadata: List<VaultSearchQualifierMetadata>
    get() = defaultVaultSearchQualifierCatalog.metadata
