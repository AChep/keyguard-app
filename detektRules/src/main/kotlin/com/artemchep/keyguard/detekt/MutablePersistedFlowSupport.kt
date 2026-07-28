package com.artemchep.keyguard.detekt

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.allOverriddenSymbols
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.types.Variance

/**
 * Shared vocabulary for the rules that guard
 * `RememberStateFlowScopeSub.mutablePersistedFlow`.
 */
internal object MutablePersistedFlow {
    const val NAME = "mutablePersistedFlow"
    const val CALLABLE_ID = "com.artemchep.keyguard.feature.navigation.state." +
        "RememberStateFlowScopeSub.mutablePersistedFlow"

    const val KEY_PARAMETER = "key"
    const val STORAGE_PARAMETER = "storage"
    const val INITIAL_VALUE_PARAMETER = "initialValue"

    const val VALUE_TYPE_PARAMETER = "T"
    const val SERIALIZED_TYPE_PARAMETER = "S"
}

/**
 * The type that actually reaches storage: the serialized `S` of the serializer overload, or
 * `T` for the plain one.
 */
internal class PersistedType(
    val typeParameterName: String,
    val type: KaType,
    val isSerialized: Boolean,
)

/**
 * Whether [symbol] is the guarded function, including the overrides of it. A call site that
 * statically resolves to `RememberStateFlowScopeImpl`'s override has to be checked too.
 */
internal fun KaSession.isMutablePersistedFlowCall(symbol: KaCallableSymbol): Boolean {
    if (symbol.callableId?.asSingleFqName()?.asString() == MutablePersistedFlow.CALLABLE_ID) {
        return true
    }
    return symbol.allOverriddenSymbols.any { overridden ->
        overridden.callableId?.asSingleFqName()?.asString() == MutablePersistedFlow.CALLABLE_ID
    }
}

internal fun KaFunctionCall<*>.persistedType(): PersistedType? {
    val serialized = typeArgumentsMapping.entries.firstOrNull { (parameter) ->
        parameter.name.asString() == MutablePersistedFlow.SERIALIZED_TYPE_PARAMETER
    }
    val entry = serialized
        ?: typeArgumentsMapping.entries.firstOrNull { (parameter) ->
            parameter.name.asString() == MutablePersistedFlow.VALUE_TYPE_PARAMETER
        }
        ?: return null
    return PersistedType(
        typeParameterName = entry.key.name.asString(),
        type = entry.value,
        isSerialized = serialized != null,
    )
}

internal fun KaFunctionCall<*>.argumentFor(parameterName: String): KtExpression? =
    valueArgumentMapping.entries
        .firstOrNull { (_, parameter) -> parameter.name.asString() == parameterName }
        ?.key

/**
 * A cheap syntactic pre-filter before resolving a call. Resolution remains the source of truth;
 * this only avoids resolving every call expression in the file.
 */
internal fun KtCallExpression.isMutablePersistedFlowCandidate(): Boolean {
    val referencedName = (calleeExpression as? KtSimpleNameExpression)
        ?.getReferencedName()
        ?: return false
    if (referencedName == MutablePersistedFlow.NAME) {
        return true
    }

    val file = containingFile as? KtFile ?: return false
    return file.importDirectives.any { directive ->
        directive.aliasName == referencedName &&
            directive.importedFqName?.shortName()?.asString() == MutablePersistedFlow.NAME
    }
}

/** A qualified, source-like spelling for diagnostics. Type identity is compared semantically. */
@OptIn(KaExperimentalApi::class)
internal fun KaSession.renderTypeForMessage(type: KaType): String =
    type.render(
        renderer = KaTypeRendererForSource.WITH_QUALIFIED_NAMES,
        position = Variance.INVARIANT,
    )
