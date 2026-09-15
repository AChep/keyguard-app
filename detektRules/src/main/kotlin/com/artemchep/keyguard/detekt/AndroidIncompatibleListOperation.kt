package com.artemchep.keyguard.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.containingDeclaration
import org.jetbrains.kotlin.analysis.api.components.defaultType
import org.jetbrains.kotlin.analysis.api.components.fakeOverrideOriginal
import org.jetbrains.kotlin.analysis.api.components.isSubtypeOf
import org.jetbrains.kotlin.analysis.api.components.resolveToCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression

/**
 * Prevents calls and callable references that Kotlin resolves to the Java 21 `List.removeFirst` and
 * `List.removeLast` members added in Android API 35.
 *
 * Kotlin has same-named `MutableList` extensions. Once the project compiles against API 35 or
 * newer, the Java members take precedence and the generated Android bytecode calls them
 * directly. That bytecode throws `NoSuchMethodError` on Android 14 and older.
 *
 * A call is unsafe when it resolves to a Java library member and the receiver's static type is
 * not a `java.util.Deque`: the deque members exist on every Android version, while every other
 * Java owner (`List`, `ArrayList`, `SequencedCollection`, user subclasses of them) only gained
 * the methods in API 35. Kotlin's `ArrayDeque` and hand-written members are not Java members
 * and are never reported.
 */
class AndroidIncompatibleListOperation(
    config: Config,
) : Rule(
    config,
    description = "Reports List.removeFirst/removeLast calls that require Android API 35.",
),
    RequiresAnalysisApi {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val callee = expression.calleeExpression as? KtSimpleNameExpression ?: return
        checkOperation(expression, callee)
    }

    override fun visitCallableReferenceExpression(expression: KtCallableReferenceExpression) {
        super.visitCallableReferenceExpression(expression)

        checkOperation(expression, expression.callableReference)
    }

    private fun checkOperation(expression: KtExpression, callee: KtSimpleNameExpression) {
        val operation = callee.getReferencedName()
        val replacement = REPLACEMENTS[operation] ?: return
        val suggestion = if (expression is KtCallableReferenceExpression) {
            "Use a lambda that calls $replacement instead."
        } else {
            "Use $replacement instead."
        }

        val verdict = analyze(expression) {
            val call = expression.resolveToCall()?.successfulFunctionCallOrNull()
                ?: return@analyze Verdict.UNVERIFIED
            val applied = call.partiallyAppliedSymbol
            // A subclass inherits the member as a fake override owned by the subclass; the
            // original tells where the method really comes from.
            val original = applied.signature.symbol.fakeOverrideOriginal
            if (original.origin !in JAVA_ORIGINS || (original as? KaNamedFunctionSymbol)?.isStatic == true) {
                return@analyze Verdict.SAFE
            }
            // Unbound references have no dispatch receiver value. The declaring class still
            // distinguishes the old Deque methods from the Java 21 list methods.
            val receiverType = applied.dispatchReceiver?.type
                ?: (original.containingDeclaration as? KaClassSymbol)?.defaultType
                ?: return@analyze Verdict.UNVERIFIED
            if (receiverType.isSubtypeOf(DEQUE)) Verdict.SAFE else Verdict.UNSAFE
        }
        val message = when (verdict) {
            Verdict.SAFE -> return
            Verdict.UNVERIFIED ->
                "Unable to verify this removeFirst/removeLast operation for Android API 35 " +
                    "compatibility. Fix the surrounding type-resolution errors."
            Verdict.UNSAFE ->
                "$operation() resolves to the Java API introduced in Android 15 (API 35) " +
                    "and throws NoSuchMethodError on older Android versions. " +
                    suggestion
        }
        report(Finding(Entity.from(callee), message))
    }

    private enum class Verdict { SAFE, UNSAFE, UNVERIFIED }

    private companion object {
        /** Guarded operation name to the replacement that is safe on every Android version. */
        val REPLACEMENTS = mapOf(
            "removeFirst" to "removeAt(0)",
            "removeLast" to "removeAt(lastIndex)",
        )

        val DEQUE = ClassId.topLevel(FqName("java.util.Deque"))

        /**
         * The JDK members mapped onto Kotlin's `MutableList` builtin report `JAVA_SOURCE`, not
         * `JAVA_LIBRARY`, so both Java origins count.
         */
        val JAVA_ORIGINS = setOf(KaSymbolOrigin.JAVA_LIBRARY, KaSymbolOrigin.JAVA_SOURCE)
    }
}
