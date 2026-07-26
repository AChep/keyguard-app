package com.artemchep.keyguard.detekt

import dev.detekt.api.Config
import dev.detekt.test.junit.KotlinCoreEnvironmentTest
import dev.detekt.test.lintWithContext
import dev.detekt.test.utils.KotlinEnvironmentContainer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@KotlinCoreEnvironmentTest
class MutablePersistedFlowDuplicateKeyTest {
    private fun lint(
        env: KotlinEnvironmentContainer,
        declarations: String,
        imports: String = "",
        additionalSources: List<String> = emptyList(),
    ) = MutablePersistedFlowDuplicateKey(Config.empty)
        .lintWithContext(
            env,
            wrap(declarations, imports),
            STATE_STUB,
            *additionalSources.toTypedArray(),
        )

    private fun messages(
        env: KotlinEnvironmentContainer,
        declarations: String,
    ) = lint(env, declarations).map { it.message }

    //
    // Reported
    //

    @Test
    fun `reports the same key persisting two different types`(env: KotlinEnvironmentContainer) {
        val findings = lint(
            env,
            """
            fun Scope.producer() {
                val a = mutablePersistedFlow("state") { "text" }
                val b = mutablePersistedFlow("state") { 1L }
            }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
        val message = findings.single().message
        assertTrue(message.contains("\"state\""), "was: $message")
        assertTrue(message.contains("kotlin.Long"), "was: $message")
        assertTrue(message.contains("kotlin.String"), "was: $message")
    }

    @Test
    fun `reports a conflict across nested lambdas in the same scope`(
        env: KotlinEnvironmentContainer,
    ) {
        // The scope receiver is the same inside a lambda, so the registry is shared.
        val findings = lint(
            env,
            """
            fun Scope.producer(items: List<String>) {
                val a = mutablePersistedFlow("state") { "text" }
                items.map {
                    mutablePersistedFlow("state") { true }
                }
            }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `resolves a key given as a named constant`(env: KotlinEnvironmentContainer) {
        val findings = lint(
            env,
            """
            const val STATE_KEY = "state"

            fun Scope.producer() {
                val a = mutablePersistedFlow(STATE_KEY) { "text" }
                val b = mutablePersistedFlow("state") { 1L }
            }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports each conflicting call when three types share a key`(
        env: KotlinEnvironmentContainer,
    ) {
        val findings = lint(
            env,
            """
            fun Scope.producer() {
                val a = mutablePersistedFlow("state") { "text" }
                val b = mutablePersistedFlow("state") { 1L }
                val c = mutablePersistedFlow("state") { true }
            }
            """.trimIndent(),
        )

        assertEquals(2, findings.size)
    }

    @Test
    fun `compares the serialized type rather than the value type`(
        env: KotlinEnvironmentContainer,
    ) {
        // Both persist String, so there is no conflict despite the differing value types.
        val messages = messages(
            env,
            """
            fun Scope.producer() {
                val a = mutablePersistedFlow("state") { "text" }
                val b = mutablePersistedFlow<Long, String>(
                    key = "state",
                    serialize = { _, value -> value.toString() },
                    deserialize = { _, value -> value.toLong() },
                    initialValue = { 1L },
                )
            }
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun `reports nullable and non-null persisted types as a conflict`(
        env: KotlinEnvironmentContainer,
    ) {
        val findings = lint(
            env,
            """
            fun Scope.producer() {
                val a = mutablePersistedFlow<String>("state") { "text" }
                val b = mutablePersistedFlow<String?>("state") { null }
            }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
        val message = findings.single().message
        assertTrue(message.contains("kotlin.String?"), "was: $message")
        assertTrue(message.contains("kotlin.String"), "was: $message")
    }

    @Test
    fun `reports nested generic nullability as a conflict`(
        env: KotlinEnvironmentContainer,
    ) {
        val findings = lint(
            env,
            """
            fun Scope.producer() {
                val a = mutablePersistedFlow<List<String>>("state") { listOf("text") }
                val b = mutablePersistedFlow<List<String?>>("state") { listOf(null) }
            }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
        assertTrue(
            findings.single().message.contains("kotlin.String?"),
            "was: ${findings.single().message}",
        )
    }

    @Test
    fun `reports nullable serialized type against non-null plain type`(
        env: KotlinEnvironmentContainer,
    ) {
        val findings = lint(
            env,
            """
            fun Scope.producer() {
                val a = mutablePersistedFlow("state") { "text" }
                val b = mutablePersistedFlow<Long, String?>(
                    key = "state",
                    serialize = { _, value -> value.toString() },
                    deserialize = { _, value -> value?.toLong() ?: 0L },
                    initialValue = { 1L },
                )
            }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports a conflict through an escaped callable name`(
        env: KotlinEnvironmentContainer,
    ) {
        val findings = lint(
            env,
            """
            fun Scope.producer() {
                val a = mutablePersistedFlow("state") { "text" }
                val b = `mutablePersistedFlow`("state") { 1L }
            }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports a conflict through an imported object member alias`(
        env: KotlinEnvironmentContainer,
    ) {
        val findings = lint(
            env = env,
            imports = "import com.artemchep.keyguard.fixture.ObjectScope.mutablePersistedFlow as persist",
            declarations = """
                fun producer() {
                    val a = persist("state") { "text" }
                    val b = persist("state") { 1L }
                }
            """.trimIndent(),
            additionalSources = listOf(OBJECT_SCOPE_STUB),
        )

        assertEquals(1, findings.size)
    }

    //
    // Not reported
    //

    @Test
    fun `accepts the same key with the same type in exclusive branches`(
        env: KotlinEnvironmentContainer,
    ) {
        // This mirrors the storage-dependent if/else that real producers use: only one branch
        // runs, and sharing a sink of the same type is harmless anyway.
        val messages = messages(
            env,
            """
            fun Scope.producer(useDisk: Boolean, disk: DiskHandle) {
                val a = if (useDisk) {
                    mutablePersistedFlow(
                        key = "keyboard",
                        storage = PersistedStorage.InDisk(disk),
                    ) { false }
                } else {
                    mutablePersistedFlow(key = "keyboard") { false }
                }
            }
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun `accepts the same nullable persisted type`(env: KotlinEnvironmentContainer) {
        val messages = messages(
            env,
            """
            fun Scope.producer() {
                val a = mutablePersistedFlow<String?>("state") { null }
                val b = mutablePersistedFlow<String?>("state") { "text" }
            }
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun `treats a type alias as its expanded type`(env: KotlinEnvironmentContainer) {
        val messages = messages(
            env,
            """
            typealias Text = String

            fun Scope.producer() {
                val a = mutablePersistedFlow<Text>("state") { "text" }
                val b = mutablePersistedFlow<String>("state") { "text" }
            }
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun `accepts distinct keys`(env: KotlinEnvironmentContainer) {
        val messages = messages(
            env,
            """
            fun Scope.producer() {
                val a = mutablePersistedFlow("sort") { "text" }
                val b = mutablePersistedFlow("sort_persistent") { 1L }
            }
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun `ignores keys it cannot evaluate`(env: KotlinEnvironmentContainer) {
        // A runtime-built key may or may not collide; guessing would only add noise.
        val messages = messages(
            env,
            """
            fun Scope.producer(prefix: String) {
                val a = mutablePersistedFlow(prefix + ".a") { "text" }
                val b = mutablePersistedFlow(prefix + ".a") { 1L }
            }
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun `treats separate top-level functions as separate scopes`(
        env: KotlinEnvironmentContainer,
    ) {
        val messages = messages(
            env,
            """
            fun Scope.first() {
                val a = mutablePersistedFlow("state") { "text" }
            }

            fun Scope.second() {
                val b = mutablePersistedFlow("state") { 1L }
            }
            """.trimIndent(),
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

            fun producer() {
                val u = Unrelated()
                val a = u.mutablePersistedFlow("state") { "text" }
                val b = u.mutablePersistedFlow("state") { 1L }
            }
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), messages)
    }

    @Test
    fun `ignores an unrelated imported object member alias`(
        env: KotlinEnvironmentContainer,
    ) {
        val findings = lint(
            env = env,
            imports = "import com.artemchep.keyguard.fixture.Unrelated.mutablePersistedFlow as persist",
            declarations = """
                fun producer() {
                    val a = persist("state") { "text" }
                    val b = persist("state") { 1L }
                }
            """.trimIndent(),
            additionalSources = listOf(UNRELATED_OBJECT_STUB),
        )

        assertEquals(0, findings.size)
    }

    @Test
    fun `reports a conflict only once for a local function inside the scope`(
        env: KotlinEnvironmentContainer,
    ) {
        // The outermost function owns the whole body, so the nested declaration must not make
        // the same conflict be reported twice.
        val findings = lint(
            env,
            """
            fun Scope.producer() {
                val a = mutablePersistedFlow("state") { "text" }

                fun nested() {
                    mutablePersistedFlow("state") { 1L }
                }
            }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun `reports candidates whose type resolution fails`(env: KotlinEnvironmentContainer) {
        val findings = MutablePersistedFlowDuplicateKey(Config.empty).lintWithContext(
            env,
            """
            package com.artemchep.keyguard.test

            fun producer() {
                thisDoesNotExist.mutablePersistedFlow("state") { "text" }
                thisDoesNotExist.mutablePersistedFlow("state") { 1L }
            }
            """.trimIndent(),
            STATE_STUB,
            allowCompilationErrors = true,
        )

        assertEquals(2, findings.size)
        assertTrue(
            findings.all { it.message.contains("Unable to verify") },
            "was: ${findings.map { it.message }}",
        )
    }

    private companion object {
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

        val OBJECT_SCOPE_STUB = """
            package com.artemchep.keyguard.fixture

            import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
            import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScopeSub
            import kotlinx.serialization.json.Json

            object ObjectScope : RememberStateFlowScopeSub {
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
        """.trimIndent()

        val UNRELATED_OBJECT_STUB = """
            package com.artemchep.keyguard.fixture

            object Unrelated {
                fun <T> mutablePersistedFlow(key: String, initialValue: () -> T): T =
                    initialValue()
            }
        """.trimIndent()

        fun wrap(declarations: String, imports: String = "") = """
            package com.artemchep.keyguard.test

            import com.artemchep.keyguard.feature.navigation.state.DiskHandle
            import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
            import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScopeSub
            $imports

            typealias Scope = RememberStateFlowScopeSub

            $declarations
        """.trimIndent()
    }
}
