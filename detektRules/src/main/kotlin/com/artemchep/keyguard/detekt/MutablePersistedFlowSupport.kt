package com.artemchep.keyguard.detekt

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.allOverriddenSymbols
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaStarTypeProjection
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtExpression

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

/** A stable, readable spelling of a type, used both in messages and to compare two types. */
internal fun KaType.renderForMessage(): String {
    val classType = this as? KaClassType ?: return toString()
    val name = classType.classId.asSingleFqName().asString()
    if (classType.typeArguments.isEmpty()) {
        return name
    }
    return classType.typeArguments.joinToString(prefix = "$name<", postfix = ">") { projection ->
        if (projection is KaStarTypeProjection) "*" else projection.type?.renderForMessage() ?: "*"
    }
}
