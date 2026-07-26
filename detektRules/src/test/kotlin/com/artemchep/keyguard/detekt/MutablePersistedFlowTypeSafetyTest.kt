package com.artemchep.keyguard.detekt

import dev.detekt.api.Config
import dev.detekt.test.junit.KotlinCoreEnvironmentTest
import dev.detekt.test.lintWithContext
import dev.detekt.test.utils.KotlinEnvironmentContainer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@KotlinCoreEnvironmentTest
class MutablePersistedFlowTypeSafetyTest {
    /** Lints [declarations] as top-level code in a file that can see the stubbed API. */
    private fun lint(
        env: KotlinEnvironmentContainer,
        declarations: String,
    ) = MutablePersistedFlowTypeSafety(Config.empty)
        .lintWithContext(env, wrap(declarations), STATE_STUB, PLATFORM_STUB, PARCELIZE_STUB)

    private fun messages(
        env: KotlinEnvironmentContainer,
        declarations: String,
    ) = lint(env, declarations).map { it.message }

    //
    // Accepted
    //

    @Test
    fun `accepts bundle-safe scalars and nullable scalars`(env: KotlinEnvironmentContainer) {
        val messages = messages(
            env,
            """
            val a = scope.mutablePersistedFlow("a") { "text" }
            val b = scope.mutablePersistedFlow("b") { true }
            val c = scope.mutablePersistedFlow("c") { 1 }
            val d = scope.mutablePersistedFlow("d") { 1L }
            val e = scope.mutablePersistedFlow<String?>("e") { null }
            val f = scope.mutablePersistedFlow<Int?>("f") { null }
            val g = scope.mutablePersistedFlow("g") { 'x' }
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun `accepts parcelable and serializable types`(env: KotlinEnvironmentContainer) {
        val messages = messages(
            env,
            """
            val a = scope.mutablePersistedFlow("a") { Parcelled() }
            val b = scope.mutablePersistedFlow("b") { Serialized() }
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun `accepts an enum even though kotlin Enum declares no supertype`(
        env: KotlinEnvironmentContainer,
    ) {
        val messages = messages(env, """val a = scope.mutablePersistedFlow("a") { Mode.FIRST }""")

        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun `accepts a type parameter whose upper bound is safe`(env: KotlinEnvironmentContainer) {
        val messages = messages(
            env,
            """
            fun <T : Serialized> Scope.persist(value: T) =
                mutablePersistedFlow("a") { value }
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun `rejects a type parameter whose upper bound is unsafe`(env: KotlinEnvironmentContainer) {
        // Counterpart to the test above: proves that case passes because the bound is safe,
        // not because a generic call is skipped altogether.
        val findings = lint(
            env,
            """
            fun <T : Custom> Scope.persist(value: T) =
                mutablePersistedFlow("a") { value }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("Bundle-safe"))
    }

    @Test
    fun `accepts collections whose element types are safe`(env: KotlinEnvironmentContainer) {
        val messages = messages(
            env,
            """
            val a = scope.mutablePersistedFlow("a") { listOf("x") }
            val b = scope.mutablePersistedFlow("b") { setOf("x") }
            val c = scope.mutablePersistedFlow("c") { mapOf("k" to "v") }
            val d = scope.mutablePersistedFlow("d") { arrayOf("x") }
            val e = scope.mutablePersistedFlow("e") { intArrayOf(1) }
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun `validates the serialized type rather than the value type`(
        env: KotlinEnvironmentContainer,
    ) {
        // Custom is not bundle-safe, but it never reaches storage -- only String does.
        val messages = messages(
            env,
            """
            val a = scope.mutablePersistedFlow<Custom, String>(
                key = "a",
                serialize = { _, value -> value.text },
                deserialize = { _, value -> Custom(value) },
                initialValue = { Custom("x") },
            )
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun `accepts json-safe scalars for disk storage`(env: KotlinEnvironmentContainer) {
        // Long, Double and Number all survive extractedContent, as do Boolean and String.
        val messages = messages(
            env,
            """
            val a = scope.mutablePersistedFlow("a", PersistedStorage.InDisk(disk)) { 1L }
            val b = scope.mutablePersistedFlow("b", PersistedStorage.InDisk(disk)) { 1.5 }
            val c = scope.mutablePersistedFlow("c", PersistedStorage.InDisk(disk)) { "text" }
            val d = scope.mutablePersistedFlow("d", PersistedStorage.InDisk(disk)) { true }
            val e = scope.mutablePersistedFlow<Number>("e", PersistedStorage.InDisk(disk)) { 1L }
            val f = scope.mutablePersistedFlow("f", PersistedStorage.InDisk(disk)) { listOf("x") }
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun `accepts a homogeneous string map for disk storage`(env: KotlinEnvironmentContainer) {
        val messages = messages(
            env,
            """
            val a = scope.mutablePersistedFlow<Custom, Map<String, String>>(
                key = "a",
                storage = PersistedStorage.InDisk(disk),
                serialize = { _, value -> mapOf("text" to value.text) },
                deserialize = { _, value -> Custom(value.getValue("text")) },
                initialValue = { Custom("x") },
            )
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun `still accepts a widening-prone number for in-memory storage`(
        env: KotlinEnvironmentContainer,
    ) {
        val messages = messages(
            env,
            """val a = scope.mutablePersistedFlow("a", PersistedStorage.InMemory) { 1 }""",
        )

        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun `ignores an unrelated function of the same name`(env: KotlinEnvironmentContainer) {
        val messages = messages(
            env,
            """
            class Unrelated {
                fun <T> mutablePersistedFlow(key: String, initialValue: () -> T): T =
                    initialValue()
            }

            val a = Unrelated().mutablePersistedFlow("a") { Custom("x") }
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), messages)
    }

    //
    // Rejected
    //

    @Test
    fun `rejects a custom type on the plain overload`(env: KotlinEnvironmentContainer) {
        val findings = lint(env, """val a = scope.mutablePersistedFlow("a") { Custom("x") }""")

        assertEquals(1, findings.size)
        val message = findings.single().message
        assertTrue(message.contains("T=com.artemchep.keyguard.test.Custom"), "was: $message")
        assertTrue(message.contains("Bundle-safe"), "was: $message")
    }

    @Test
    fun `rejects an unsafe serialized type`(env: KotlinEnvironmentContainer) {
        val findings = lint(
            env,
            """
            val a = scope.mutablePersistedFlow<String, Custom>(
                key = "a",
                serialize = { _, value -> Custom(value) },
                deserialize = { _, value -> value.text },
                initialValue = { "x" },
            )
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
        val message = findings.single().message
        assertTrue(message.contains("S=com.artemchep.keyguard.test.Custom"), "was: $message")
        assertTrue(message.contains("Change serialize/deserialize"), "was: $message")
    }

    @Test
    fun `rejects Any as the persisted type`(env: KotlinEnvironmentContainer) {
        val findings = lint(env, """val a = scope.mutablePersistedFlow<Any?>("a") { null }""")

        assertEquals(1, findings.size)
    }

    @Test
    fun `rejects a map with an unsafe value type`(env: KotlinEnvironmentContainer) {
        val findings = lint(
            env,
            """val a = scope.mutablePersistedFlow<Map<String, Any?>>("a") { emptyMap() }""",
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `rejects a collection with an unsafe element type`(env: KotlinEnvironmentContainer) {
        val findings = lint(
            env,
            """val a = scope.mutablePersistedFlow<List<Custom>>("a") { emptyList() }""",
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `rejects star-projected collection elements`(env: KotlinEnvironmentContainer) {
        val findings = lint(
            env,
            """val a = scope.mutablePersistedFlow<List<*>>("a") { emptyList<String>() }""",
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `rejects numbers that a disk round trip widens`(env: KotlinEnvironmentContainer) {
        val findings = lint(
            env,
            """
            val a = scope.mutablePersistedFlow("a", PersistedStorage.InDisk(disk)) { 1 }
            val b = scope.mutablePersistedFlow("b", PersistedStorage.InDisk(disk)) { 1.5f }
            val c = scope.mutablePersistedFlow<Short>("c", PersistedStorage.InDisk(disk)) { 1 }
            val d = scope.mutablePersistedFlow("d", PersistedStorage.InDisk(disk)) { 'x' }
            """.trimIndent(),
        )

        assertEquals(4, findings.size)
        assertTrue(
            findings.all { it.message.contains("JSON-safe") },
            "was: ${findings.map { it.message }}",
        )
    }

    @Test
    fun `rejects an identity serializer that leaves S inferred as Int`(
        env: KotlinEnvironmentContainer,
    ) {
        // Declaring the deserialize parameter as Number does not widen S: S is inferred from
        // the serialize lambda's return type, so this still persists an Int.
        val findings = lint(
            env,
            """
            val a = scope.mutablePersistedFlow(
                key = "a",
                storage = PersistedStorage.InDisk(disk),
                serialize = { _, value -> value },
                deserialize = { _, value: Number -> value.toInt() },
                initialValue = { -1 },
            )
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
        val message = findings.single().message
        assertTrue(message.contains("S=kotlin.Int"), "was: $message")
    }

    @Test
    fun `accepts the same call once S is pinned to Number explicitly`(
        env: KotlinEnvironmentContainer,
    ) {
        val messages = messages(
            env,
            """
            val a = scope.mutablePersistedFlow<Int, Number>(
                key = "a",
                storage = PersistedStorage.InDisk(disk),
                serialize = { _, value -> value },
                deserialize = { _, value -> value.toInt() },
                initialValue = { -1 },
            )
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun `rejects an enum for disk storage because toJson can not encode it`(
        env: KotlinEnvironmentContainer,
    ) {
        val findings = lint(
            env,
            """
            val a = scope.mutablePersistedFlow("a", PersistedStorage.InDisk(disk)) { Mode.FIRST }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("JSON-safe"))
    }

    @Test
    fun `rejects a set for disk storage because toJson only handles map and list`(
        env: KotlinEnvironmentContainer,
    ) {
        val findings = lint(
            env,
            """
            val a = scope.mutablePersistedFlow("a", PersistedStorage.InDisk(disk)) { setOf("x") }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("JSON-safe"))
    }

    @Test
    fun `rejects a non-string map key for disk storage`(env: KotlinEnvironmentContainer) {
        val findings = lint(
            env,
            """
            val a = scope.mutablePersistedFlow<Map<Long, String>>(
                "a",
                PersistedStorage.InDisk(disk),
            ) { emptyMap() }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `rejects JsonObject because a disk round trip rebuilds it as a plain map`(
        env: KotlinEnvironmentContainer,
    ) {
        val findings = lint(
            env,
            """
            val a = scope.mutablePersistedFlow<JsonObject>(
                "a",
                PersistedStorage.InDisk(disk),
            ) { JsonObject(emptyMap()) }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `rejects JsonObject for the bundle channel too`(env: KotlinEnvironmentContainer) {
        // JsonObject is a Map<String, JsonElement>, so it must not slip through the map branch.
        val findings = lint(
            env,
            """val a = scope.mutablePersistedFlow<JsonObject>("a") { JsonObject(emptyMap()) }""",
        )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("Bundle-safe"))
    }

    @Test
    fun `rejects a map key view even though its declared type is a safe Set`(
        env: KotlinEnvironmentContainer,
    ) {
        val findings = lint(
            env,
            """
            val source = mapOf("k" to "v")
            val a = scope.mutablePersistedFlow("a") { source.keys }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
        val message = findings.single().message
        assertTrue(message.contains("kotlin.collections.Map.keys"), "was: $message")
        assertTrue(message.contains("toSet()"), "was: $message")
    }

    @Test
    fun `rejects a map values view`(env: KotlinEnvironmentContainer) {
        val findings = lint(
            env,
            """
            val source = mapOf("k" to "v")
            val a = scope.mutablePersistedFlow("a") { source.values }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `accepts a copy of a map key view`(env: KotlinEnvironmentContainer) {
        // Counterpart to the two tests above: proves they fail because of the view, not
        // because any Set-typed initial value is rejected.
        val messages = messages(
            env,
            """
            val source = mapOf("k" to "v")
            val a = scope.mutablePersistedFlow("a") { source.keys.toSet() }
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun `rejects an immutable collection that is not Serializable`(
        env: KotlinEnvironmentContainer,
    ) {
        val findings = lint(
            env,
            """
            val a = scope.mutablePersistedFlow<PersistentList<String>>("a") { persistentListOf() }
            val b = scope.mutablePersistedFlow<PersistentMap<String, String>>("b") {
                persistentMapOf()
            }
            """.trimIndent(),
        )

        assertEquals(2, findings.size)
        assertTrue(
            findings.all { it.message.contains("Bundle-safe") },
            "was: ${findings.map { it.message }}",
        )
    }

    @Test
    fun `treats an unknown storage value as potentially disk-backed`(
        env: KotlinEnvironmentContainer,
    ) {
        // `storage` is only known to be a PersistedStorage, so the stricter rule must apply.
        val findings = lint(
            env,
            """
            fun Scope.persist(storage: PersistedStorage) =
                mutablePersistedFlow("a", storage) { 1 }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("JSON-safe"))
    }

    @Test
    fun `checks a call that resolves to an override of the interface member`(
        env: KotlinEnvironmentContainer,
    ) {
        val findings = lint(
            env,
            """
            class ScopeImpl : Scope {
                override fun <T> mutablePersistedFlow(
                    key: String,
                    storage: PersistedStorage,
                    initialValue: () -> T,
                ): T = initialValue()

                override fun <T, S> mutablePersistedFlow(
                    key: String,
                    storage: PersistedStorage,
                    serialize: (Json, T) -> S,
                    deserialize: (Json, S) -> T,
                    initialValue: () -> T,
                ): T = initialValue()
            }

            val a = ScopeImpl().mutablePersistedFlow("a") { Custom("x") }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports a call whose type resolution fails`(env: KotlinEnvironmentContainer) {
        val findings = MutablePersistedFlowTypeSafety(Config.empty).lintWithContext(
            env,
            """
            package com.artemchep.keyguard.test

            fun body() {
                thisDoesNotExist.mutablePersistedFlow("a") { "x" }
            }
            """.trimIndent(),
            STATE_STUB,
            PLATFORM_STUB,
            PARCELIZE_STUB,
            allowCompilationErrors = true,
        )

        assertEquals(1, findings.size)
        assertTrue(
            findings.single().message.contains("Unable to verify"),
            "was: ${findings.single().message}",
        )
    }

    private companion object {
        /** Mirrors the real API shape closely enough for resolution: both overloads and storage. */
        val STATE_STUB = """
            package com.artemchep.keyguard.feature.navigation.state

            import kotlinx.serialization.json.Json

            interface DiskHandle

            sealed interface PersistedStorage {
                data object InMemory : PersistedStorage
                data class InDisk(val disk: DiskHandle) : PersistedStorage
            }

            interface RememberStateFlowScopeSub {
                fun <T> mutablePersistedFlow(
                    key: String,
                    storage: PersistedStorage = PersistedStorage.InMemory,
                    initialValue: () -> T,
                ): T

                fun <T, S> mutablePersistedFlow(
                    key: String,
                    storage: PersistedStorage = PersistedStorage.InMemory,
                    serialize: (Json, T) -> S,
                    deserialize: (Json, S) -> T,
                    initialValue: () -> T,
                ): T
            }
        """.trimIndent()

        /**
         * The repository's multiplatform stand-ins for Parcelable/Serializable. In `commonMain`
         * these are `expect interface`s with no JVM supertype, which is exactly why the rule has
         * to match them by name rather than through `java.io.Serializable`.
         */
        val PLATFORM_STUB = """
            package com.artemchep.keyguard.platform

            interface LeSerializable
        """.trimIndent()

        val PARCELIZE_STUB = """
            package com.artemchep.keyguard.platform.parcelize

            interface LeParcelable
        """.trimIndent()

        fun wrap(declarations: String) = """
            package com.artemchep.keyguard.test

            import com.artemchep.keyguard.feature.navigation.state.DiskHandle
            import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
            import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScopeSub
            import com.artemchep.keyguard.platform.LeSerializable
            import com.artemchep.keyguard.platform.parcelize.LeParcelable
            import kotlinx.collections.immutable.PersistentList
            import kotlinx.collections.immutable.PersistentMap
            import kotlinx.collections.immutable.persistentListOf
            import kotlinx.collections.immutable.persistentMapOf
            import kotlinx.serialization.json.Json
            import kotlinx.serialization.json.JsonObject

            typealias Scope = RememberStateFlowScopeSub

            class Custom(val text: String)

            class Parcelled : LeParcelable

            class Serialized : LeSerializable

            enum class Mode { FIRST, SECOND }

            val scope: Scope = TODO()
            val disk: DiskHandle = TODO()

            $declarations
        """.trimIndent()
    }
}
