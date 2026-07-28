package com.artemchep.keyguard.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.base.KaConstantValue
import org.jetbrains.kotlin.analysis.api.components.evaluate
import org.jetbrains.kotlin.analysis.api.components.resolveToCall
import org.jetbrains.kotlin.analysis.api.components.semanticallyEquals
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/**
 * `RememberStateFlowScopeImpl` keeps one `registry` per scope, keyed by the `key` string alone,
 * and hands out entries with `getOrPut`. Two calls that share a key therefore share a single
 * sink: the second call's `initialValue` and `storage` are dropped, and the sink is handed back
 * through an unchecked cast to the second call's type.
 *
 * Sharing a key with the *same* type is harmless and intentional in places, for example the two
 * branches of an `if` that only differ in storage. So only a genuine type conflict is reported.
 */
class MutablePersistedFlowDuplicateKey(
    config: Config,
) : Rule(
    config,
    description = "Reports mutablePersistedFlow calls that share a key within one scope but " +
        "persist different types, which makes them alias a single sink.",
),
    RequiresAnalysisApi {

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)

        // Only the outermost function: the scope receiver is the same throughout its body,
        // including nested lambdas and local functions, and processing those separately would
        // report the same conflict twice.
        if (function.getStrictParentOfType<KtNamedFunction>() != null) {
            return
        }

        val candidates = function.collectDescendantsOfType<KtCallExpression> { call ->
            call.isMutablePersistedFlowCandidate()
        }
        if (candidates.size < 2) {
            return
        }

        analyze(function) {
            val byKey = linkedMapOf<String, MutableList<KeyedCall>>()
            for (candidate in candidates) {
                val call = candidate.resolveToCall()?.successfulFunctionCallOrNull()
                if (call == null) {
                    reportUnverifiedCall(candidate)
                    continue
                }
                if (!isMutablePersistedFlowCall(call.signature.symbol)) {
                    continue
                }
                // Only constant keys can be compared. `evaluate` also folds `const val`
                // references, so a named constant is still seen; a key built at runtime is not.
                val keyExpression = call.argumentFor(MutablePersistedFlow.KEY_PARAMETER) ?: continue
                val key = (keyExpression.evaluate() as? KaConstantValue.StringValue)?.value
                    ?: continue
                val type = call.persistedType()?.type
                if (type == null) {
                    reportUnverifiedCall(candidate)
                    continue
                }
                if (type is KaErrorType) {
                    reportUnverifiedCall(candidate)
                    continue
                }
                byKey.getOrPut(key) { mutableListOf() } += KeyedCall(
                    expression = candidate,
                    type = type,
                    typeText = renderTypeForMessage(type),
                )
            }

            for ((key, calls) in byKey) {
                val first = calls.first()
                // Report every call that conflicts with the first one, so each offending site
                // gets a finding rather than only the declaration order winner.
                calls.asSequence()
                    .drop(1)
                    .filterNot { it.type.semanticallyEquals(first.type) }
                    .forEach { conflicting ->
                        report(
                            Finding(
                                Entity.from(
                                    conflicting.expression.calleeExpression
                                        ?: conflicting.expression,
                                ),
                                "mutablePersistedFlow reuses the key \"$key\" within this scope " +
                                    "but persists ${conflicting.typeText} here and " +
                                    "${first.typeText} " +
                                    "elsewhere. The registry is keyed by name only, so one of " +
                                    "these receives the other's sink through an unchecked cast. " +
                                    "Use distinct keys.",
                            ),
                        )
                    }
            }
        }
    }

    private fun reportUnverifiedCall(expression: KtCallExpression) {
        report(
            Finding(
                Entity.from(expression.calleeExpression ?: expression),
                "Unable to verify this mutablePersistedFlow call for duplicate-key conflicts. " +
                    "Fix the surrounding type-resolution errors.",
            ),
        )
    }

    private class KeyedCall(
        val expression: KtCallExpression,
        // Analysis API values are session-bound. KeyedCall instances never escape analyze {}.
        val type: KaType,
        val typeText: String,
    )
}
