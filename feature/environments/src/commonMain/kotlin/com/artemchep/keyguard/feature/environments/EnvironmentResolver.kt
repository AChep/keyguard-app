package com.artemchep.keyguard.feature.environments

enum class EnvironmentIssueType {
    InvalidName,
    DuplicateName,
    InvalidValue,
    Unset,
    UnsupportedSource,

    /** The referenced item or field does not exist in the environment's account. */
    ReferenceNotFound,

    /** More than one custom field of the referenced item has the referenced name. */
    ReferenceAmbiguous,

    /** The referenced value is hidden from the user. */
    ReferenceDenied,
}

data class EnvironmentIssue(val name: String, val type: EnvironmentIssueType)

class EnvironmentResolvedSnapshot(val variables: Map<String, String>) {
    override fun toString(): String = "EnvironmentResolvedSnapshot([redacted])"
}

sealed interface EnvironmentResolutionResult {
    data class Resolved(val snapshot: EnvironmentResolvedSnapshot) : EnvironmentResolutionResult
    data class Unresolved(val issues: List<EnvironmentIssue>) : EnvironmentResolutionResult
}

/** Finds the current value of a referenced field; the caller owns every permission check. */
fun interface EnvironmentReferenceLookup {
    fun resolve(reference: EnvironmentValueSource.Reference): EnvironmentReferenceResolution

    companion object {
        /** Resolves no reference, for callers without access to the vault. */
        val None = EnvironmentReferenceLookup { EnvironmentReferenceResolution.NotFound }
    }
}

sealed interface EnvironmentReferenceResolution {
    class Value(val value: String) : EnvironmentReferenceResolution {
        override fun toString(): String = "Value([redacted])"
    }

    data object NotFound : EnvironmentReferenceResolution

    data object Ambiguous : EnvironmentReferenceResolution

    data object Denied : EnvironmentReferenceResolution
}

object EnvironmentResolver {
    private val nameRegex = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun isValidName(name: String): Boolean = nameRegex.matches(name)

    /** Operational limits are separate from decoding so older definitions remain editable. */
    fun fitsLimits(definition: EnvironmentDefinition): Boolean =
        definition.variables.size <= EnvironmentLimits.MAX_VARIABLES &&
            definition.variables.all { it.name.length <= EnvironmentLimits.MAX_VARIABLE_NAME_LENGTH }

    /**
     * Definition validation permits Unset and references, but never unsupported
     * source kinds or NUL values. References are only resolved before delivery.
     */
    fun validate(definition: EnvironmentDefinition): List<EnvironmentIssue> = buildList {
        val names = mutableSetOf<String>()
        definition.variables.forEach { variable ->
            if (!isValidName(variable.name)) {
                add(EnvironmentIssue(variable.name, EnvironmentIssueType.InvalidName))
            }
            if (!names.add(variable.name)) {
                add(EnvironmentIssue(variable.name, EnvironmentIssueType.DuplicateName))
            }
            when (val source = variable.source) {
                is EnvironmentValueSource.Literal -> if ('\u0000' in source.value) {
                    add(EnvironmentIssue(variable.name, EnvironmentIssueType.InvalidValue))
                }
                is EnvironmentValueSource.Unsupported ->
                    add(EnvironmentIssue(variable.name, EnvironmentIssueType.UnsupportedSource))
                is EnvironmentValueSource.Reference,
                EnvironmentValueSource.Unset,
                -> Unit
            }
        }
    }

    /** Resolves immediately before delivery. Literal values are never interpolated or executed. */
    fun resolve(
        definition: EnvironmentDefinition,
        lookup: EnvironmentReferenceLookup = EnvironmentReferenceLookup.None,
    ): EnvironmentResolutionResult {
        val issues = validate(definition).toMutableList()
        val variables = linkedMapOf<String, String>()
        definition.variables.forEach { variable ->
            when (val source = variable.source) {
                is EnvironmentValueSource.Literal -> variables[variable.name] = source.value
                is EnvironmentValueSource.Reference -> when (val resolution = lookup.resolve(source)) {
                    is EnvironmentReferenceResolution.Value -> if ('\u0000' in resolution.value) {
                        issues += EnvironmentIssue(variable.name, EnvironmentIssueType.InvalidValue)
                    } else {
                        variables[variable.name] = resolution.value
                    }
                    EnvironmentReferenceResolution.NotFound ->
                        issues += EnvironmentIssue(variable.name, EnvironmentIssueType.ReferenceNotFound)
                    EnvironmentReferenceResolution.Ambiguous ->
                        issues += EnvironmentIssue(variable.name, EnvironmentIssueType.ReferenceAmbiguous)
                    EnvironmentReferenceResolution.Denied ->
                        issues += EnvironmentIssue(variable.name, EnvironmentIssueType.ReferenceDenied)
                }
                EnvironmentValueSource.Unset ->
                    issues += EnvironmentIssue(variable.name, EnvironmentIssueType.Unset)
                // Reported by the validation above.
                is EnvironmentValueSource.Unsupported -> Unit
            }
        }
        if (issues.isNotEmpty()) return EnvironmentResolutionResult.Unresolved(issues)
        return EnvironmentResolutionResult.Resolved(EnvironmentResolvedSnapshot(variables))
    }
}
