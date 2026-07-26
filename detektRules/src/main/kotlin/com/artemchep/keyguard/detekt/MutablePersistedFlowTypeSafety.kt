package com.artemchep.keyguard.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.expressionType
import org.jetbrains.kotlin.analysis.api.components.isSubtypeOf
import org.jetbrains.kotlin.analysis.api.components.resolveToCall
import org.jetbrains.kotlin.analysis.api.components.withNullability
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.KaVariableAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.analysis.api.types.KaStarTypeProjection
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.analysis.api.types.KaTypeProjection
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression

/**
 * `mutablePersistedFlow` persists screen state through two channels, neither of which the
 * compiler checks:
 *
 * 1. The serialized value is put into a `LeBundle`, which on Android is `Bundle` via
 *    `androidx.core.os.bundleOf` and throws for unsupported types.
 * 2. When `storage` is `PersistedStorage.InDisk`, the value additionally goes through
 *    `Any?.toJson()` and comes back via `JsonElement.extractedContent`, which resolves
 *    numbers as `Long` or `Double` only. A type that does not survive that round trip is
 *    restored with an unchecked cast that fails silently, so the persisted value is
 *    discarded and the initial value is used instead.
 *
 * The second failure mode is invisible at runtime, which is why this is a build-time check.
 */
class MutablePersistedFlowTypeSafety(
    config: Config,
) : Rule(
    config,
    description = "Ensures mutablePersistedFlow persists a Bundle-safe type, and a " +
        "JSON-safe one when the storage may be disk-backed.",
),
    RequiresAnalysisApi {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        // Resolving every call in a file is expensive, so first retain direct calls, escaped
        // identifiers, and legal import aliases. Semantic resolution below remains authoritative.
        if (!expression.isMutablePersistedFlowCandidate()) {
            return
        }

        analyze(expression) {
            val call = expression.resolveToCall()?.successfulFunctionCallOrNull()
            if (call == null) {
                // Do not let a resolution error become a silent pass.
                reportUnverifiedCall(expression)
                return@analyze
            }

            if (!isMutablePersistedFlowCall(call.signature.symbol)) {
                return@analyze
            }

            // Always validate the serialized type: converting an unsafe `T` into a safe `S` is
            // exactly what the serializer overload is for.
            val persisted = call.persistedType()
            if (persisted == null) {
                reportUnverifiedCall(expression)
                return@analyze
            }
            val persistedType = persisted.type
            if (persistedType is KaErrorType) {
                reportUnverifiedCall(expression)
                return@analyze
            }

            val bundleSafe = isBundleSafe(persistedType)
            val mayUseDisk = mayUseDisk(call)
            val jsonSafe = !mayUseDisk || isJsonSafe(persistedType)
            if (bundleSafe && jsonSafe) {
                // The declared type can be safe while the actual value is not: a map view has
                // the static type Set/Collection but an implementation that is not
                // Serializable, so the type check above cannot see it.
                reportMapViewInitialValue(call)
                return@analyze
            }

            val requirements = buildList {
                if (!bundleSafe) {
                    add("Bundle-safe")
                }
                if (!jsonSafe) {
                    add("JSON-safe, which the possibly disk-backed storage requires")
                }
            }.joinToString(" and ")
            val remediation = if (!persisted.isSerialized) {
                "Either make the type (and any type arguments) Parcelable/Serializable, or use " +
                    "the serialize/deserialize overload to persist a safe type such as String."
            } else {
                "Change serialize/deserialize to produce a safe type such as String."
            }

            report(
                Finding(
                    Entity.from(expression.calleeExpression ?: expression),
                    "mutablePersistedFlow persists " +
                        "${persisted.typeParameterName}=${renderTypeForMessage(persistedType)}, " +
                        "which is not $requirements. $remediation",
                ),
            )
        }
    }

    /**
     * `map.keys`, `map.values` and `map.entries` return live views whose implementations are
     * not `Serializable`, so putting one in a bundle throws even though the static type looks
     * fine. Only the initial value is checked: later assignments are out of reach here.
     */
    private fun KaSession.reportMapViewInitialValue(call: KaFunctionCall<*>) {
        val body = (call.argumentFor(MutablePersistedFlow.INITIAL_VALUE_PARAMETER)
            as? KtLambdaExpression)
            ?.functionLiteral
            ?.bodyExpression
            ?.statements
            ?.lastOrNull()
            ?: return
        val accessed = body.resolveToCall()
            ?.successfulCallOrNull<KaVariableAccessCall>()
            ?.signature
            ?.symbol
            ?.callableId
            ?.asSingleFqName()
            ?.asString()
            ?: return
        if (accessed !in MAP_VIEW_CALLABLE_IDS) {
            return
        }

        report(
            Finding(
                Entity.from(body),
                "mutablePersistedFlow persists $accessed, which is a live view backed by an " +
                    "implementation that is not Serializable, so storing it fails even though " +
                    "its declared type looks safe. Copy it first, for example with toSet() or " +
                    "toList().",
            ),
        )
    }

    private fun reportUnverifiedCall(expression: KtCallExpression) {
        report(
            Finding(
                Entity.from(expression.calleeExpression ?: expression),
                "Unable to verify the persisted type of this mutablePersistedFlow call. Fix the " +
                    "surrounding type-resolution errors or state a concrete, safe type explicitly.",
            ),
        )
    }

    /**
     * True unless the `storage` argument is provably [PersistedStorage.InMemory]. An omitted
     * argument uses the in-memory default; anything we can not pin down is assumed to reach
     * disk, so that an unclear call errs towards the stricter requirement.
     */
    private fun KaSession.mayUseDisk(call: KaFunctionCall<*>): Boolean {
        val storageExpression = call.argumentFor(MutablePersistedFlow.STORAGE_PARAMETER)
            ?: return false
        val storageClassId = storageExpression.expressionType
            ?.withNullability(false)
            ?.let { it as? KaClassType }
            ?.classId
            ?.asSingleFqName()
            ?.asString()
        return storageClassId != IN_MEMORY_STORAGE_CLASS_ID
    }

    /**
     * Mirrors what `androidx.core.os.bundleOf` accepts, plus the multiplatform marker
     * interfaces this repository uses to stand in for `Parcelable`/`Serializable`.
     */
    private fun KaSession.isBundleSafe(type: KaType): Boolean {
        val bare = type.withNullability(false)

        (bare as? KaTypeParameterType)?.let { typeParameter ->
            // Judge a generic by its bounds: a helper constrained to a safe supertype is safe.
            return typeParameter.symbol.upperBounds.any { isBundleSafe(it) }
        }

        val classType = bare as? KaClassType ?: return false
        val classId = classType.classId.asSingleFqName().asString()

        if (classId in BUNDLE_SCALAR_CLASS_IDS || classId in PRIMITIVE_ARRAY_CLASS_IDS) {
            return true
        }
        // Enums are Serializable on the JVM, but kotlin.Enum does not declare that in the
        // Kotlin builtins, so the subtype check below would not see it.
        if ((classType.symbol as? KaClassSymbol)?.classKind == KaClassKind.ENUM_CLASS) {
            return true
        }
        if (classId == KOTLIN_ARRAY_CLASS_ID) {
            return classType.typeArguments.singleOrNull().isSafe { isBundleSafe(it) }
        }
        // JsonObject is a Map and JsonArray is a List, but neither is Serializable and both
        // come back from disk as plain collections, so reject them before the branches below.
        if (JSON_ELEMENT_CLASS_IDS.any { bare.isSubtypeOf(it) }) {
            return false
        }
        // kotlinx.collections.immutable types implement the kotlin collection interfaces but
        // are not Serializable, so they would pass the collection branch below.
        if (UNSAFE_COLLECTION_CLASS_IDS.any { bare.isSubtypeOf(it) }) {
            return false
        }
        if (COLLECTION_CLASS_IDS.any { bare.isSubtypeOf(it) }) {
            return classType.typeArguments.isNotEmpty() &&
                classType.typeArguments.all { projection ->
                    projection.isSafe { argument -> isBundleSafe(argument) }
                }
        }

        return BUNDLE_SUPERTYPE_CLASS_IDS.any { bare.isSubtypeOf(it) }
    }

    /**
     * Mirrors `Any?.toJson()` and `JsonElement.extractedContent`. Deliberately an allowlist:
     * everything not named here (Set, enums, Parcelables, Char, Array) either makes
     * `toJson()` throw or does not survive the round trip.
     */
    private fun KaSession.isJsonSafe(type: KaType): Boolean {
        val bare = type.withNullability(false)

        (bare as? KaTypeParameterType)?.let { typeParameter ->
            return typeParameter.symbol.upperBounds.any { isJsonSafe(it) }
        }

        val classType = bare as? KaClassType ?: return false
        val classId = classType.classId.asSingleFqName().asString()

        if (classId in JSON_SCALAR_CLASS_IDS) {
            return true
        }
        if (JSON_ELEMENT_CLASS_IDS.any { bare.isSubtypeOf(it) }) {
            // Written through verbatim, but read back as a plain Map/List/scalar.
            return false
        }
        if (bare.isSubtypeOf(KOTLIN_LIST_CLASS_ID)) {
            return classType.typeArguments.singleOrNull().isSafe { isJsonSafe(it) }
        }
        if (bare.isSubtypeOf(KOTLIN_MAP_CLASS_ID)) {
            // Keys are written with `toString()`, so only a String key round-trips.
            val keyIsString = classType.typeArguments.getOrNull(0)
                ?.takeIf { it !is KaStarTypeProjection }
                ?.type
                ?.withNullability(false)
                ?.let { it as? KaClassType }
                ?.classId
                ?.asSingleFqName()
                ?.asString() == KOTLIN_STRING_CLASS_ID
            return keyIsString && classType.typeArguments.getOrNull(1).isSafe { isJsonSafe(it) }
        }

        return false
    }

    /**
     * A star projection or a missing argument means the element type is unknown, and an
     * unverifiable type can not be declared safe.
     */
    private inline fun KaTypeProjection?.isSafe(
        predicate: (KaType) -> Boolean,
    ): Boolean {
        if (this == null || this is KaStarTypeProjection) {
            return false
        }
        return type?.let(predicate) == true
    }

    private companion object {
        const val IN_MEMORY_STORAGE_CLASS_ID =
            "com.artemchep.keyguard.feature.navigation.state.PersistedStorage.InMemory"

        val MAP_VIEW_CALLABLE_IDS = setOf(
            "kotlin.collections.Map.keys",
            "kotlin.collections.Map.values",
            "kotlin.collections.Map.entries",
        )

        const val KOTLIN_ARRAY_CLASS_ID = "kotlin.Array"
        const val KOTLIN_STRING_CLASS_ID = "kotlin.String"
        const val KOTLIN_NOTHING_CLASS_ID = "kotlin.Nothing"

        val KOTLIN_LIST_CLASS_ID: ClassId = classId("kotlin.collections.List")
        val KOTLIN_SET_CLASS_ID: ClassId = classId("kotlin.collections.Set")
        val KOTLIN_MAP_CLASS_ID: ClassId = classId("kotlin.collections.Map")
        val COLLECTION_CLASS_IDS = listOf(
            KOTLIN_LIST_CLASS_ID,
            KOTLIN_SET_CLASS_ID,
            KOTLIN_MAP_CLASS_ID,
        )
        val JSON_ELEMENT_CLASS_IDS = listOf(
            classId("kotlinx.serialization.json.JsonElement"),
        )

        /** Collection types that satisfy the kotlin interfaces but are not Serializable. */
        val UNSAFE_COLLECTION_CLASS_IDS = listOf(
            classId("kotlinx.collections.immutable.ImmutableCollection"),
            classId("kotlinx.collections.immutable.ImmutableMap"),
        )

        val BUNDLE_SCALAR_CLASS_IDS = setOf(
            "kotlin.Boolean",
            "kotlin.Byte",
            "kotlin.Char",
            "kotlin.Double",
            "kotlin.Float",
            "kotlin.Int",
            "kotlin.Long",
            "kotlin.Short",
            // java.lang.Number is Serializable, but kotlin.Number declares no supertype.
            "kotlin.Number",
            KOTLIN_STRING_CLASS_ID,
            KOTLIN_NOTHING_CLASS_ID,
        )
        val PRIMITIVE_ARRAY_CLASS_IDS = setOf(
            "kotlin.BooleanArray",
            "kotlin.ByteArray",
            "kotlin.CharArray",
            "kotlin.DoubleArray",
            "kotlin.FloatArray",
            "kotlin.IntArray",
            "kotlin.LongArray",
            "kotlin.ShortArray",
        )
        val BUNDLE_SUPERTYPE_CLASS_IDS = listOf(
            classId("java.io.Serializable"),
            classId("java.lang.CharSequence"),
            classId("android.os.Bundle"),
            classId("android.os.IBinder"),
            classId("android.os.Parcelable"),
            classId("android.util.Size"),
            classId("android.util.SizeF"),
            classId("com.artemchep.keyguard.platform.LeBundle"),
            classId("com.artemchep.keyguard.platform.LeSerializable"),
            classId("com.artemchep.keyguard.platform.parcelize.LeParcelable"),
        )

        /**
         * `toJson` writes numbers as JSON numbers and `extractedContent` reads them back as
         * `Long` first, then `Double`. So `Long`, `Double` and `Number` survive, while `Int`,
         * `Short`, `Byte` widen to `Long` and `Float` widens to `Double`. `Char` is not
         * handled by `toJson` at all.
         */
        val JSON_SCALAR_CLASS_IDS = setOf(
            "kotlin.Boolean",
            "kotlin.Double",
            "kotlin.Long",
            "kotlin.Number",
            KOTLIN_STRING_CLASS_ID,
            KOTLIN_NOTHING_CLASS_ID,
        )

        fun classId(fqName: String): ClassId = ClassId.topLevel(FqName(fqName))
    }
}
