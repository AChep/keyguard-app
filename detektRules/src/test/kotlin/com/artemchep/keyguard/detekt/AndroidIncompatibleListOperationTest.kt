package com.artemchep.keyguard.detekt

import dev.detekt.api.Config
import dev.detekt.test.junit.KotlinCoreEnvironmentTest
import dev.detekt.test.lintWithContext
import dev.detekt.test.utils.KotlinEnvironmentContainer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@KotlinCoreEnvironmentTest
class AndroidIncompatibleListOperationTest {
    private fun lint(
        env: KotlinEnvironmentContainer,
        code: String,
    ) = AndroidIncompatibleListOperation(Config.empty)
        .lintWithContext(env, code)

    @Test
    fun `reports removeFirst and removeLast on mutable lists`(env: KotlinEnvironmentContainer) {
        val findings = lint(
            env,
            """
            fun drain() {
                val inferred = mutableListOf("first", "last")
                inferred.removeFirst()
                inferred.removeLast()

                val declared: MutableList<String> = arrayListOf("first", "last")
                declared.removeLast()

                val concrete = java.util.ArrayList<String>()
                concrete.add("value")
                concrete.removeFirst()
            }
            """.trimIndent(),
        )

        assertEquals(4, findings.size, findings.map { it.message }.toString())
        assertTrue(findings[0].message.contains("removeAt(0)"))
        assertTrue(findings[1].message.contains("removeAt(lastIndex)"))
        assertTrue(findings[2].message.contains("removeAt(lastIndex)"))
        assertTrue(findings[3].message.contains("removeAt(0)"))
    }

    @Test
    fun `reports inherited members on user subclasses of Java lists`(
        env: KotlinEnvironmentContainer,
    ) {
        val findings = lint(
            env,
            """
            class History : java.util.ArrayList<String>()

            fun drain(history: History) {
                history.removeLast()
                with(history) { removeFirst() }
            }
            """.trimIndent(),
        )

        assertEquals(2, findings.size, findings.map { it.message }.toString())
        assertTrue(findings[0].message.contains("removeAt(lastIndex)"))
        assertTrue(findings[1].message.contains("removeAt(0)"))
    }

    @Test
    fun `reports bound and unbound list callable references`(env: KotlinEnvironmentContainer) {
        val findings = lint(
            env,
            """
            class History : java.util.ArrayList<String>()

            fun removers(values: MutableList<String>, history: History) {
                val boundFirst = values::removeFirst
                val boundLast = values::removeLast
                val unboundFirst = MutableList<String>::removeFirst
                val unboundLast = MutableList<String>::removeLast
                val inheritedBound = history::removeLast
                val inheritedUnbound = History::removeFirst
            }
            """.trimIndent(),
        )

        assertEquals(6, findings.size, findings.map { it.message }.toString())
        assertTrue(findings.all { it.message.contains("Use a lambda") })
        assertTrue(findings[0].message.contains("removeAt(0)"))
        assertTrue(findings[1].message.contains("removeAt(lastIndex)"))
    }

    @Test
    fun `accepts bound and unbound deque callable references`(env: KotlinEnvironmentContainer) {
        val findings = lint(
            env,
            """
            fun removers(kotlinDeque: ArrayDeque<String>, javaDeque: java.util.Deque<String>) {
                val kotlinFirst = kotlinDeque::removeFirst
                val kotlinLast = kotlinDeque::removeLast
                val kotlinUnboundFirst = ArrayDeque<String>::removeFirst
                val kotlinUnboundLast = ArrayDeque<String>::removeLast
                val javaFirst = javaDeque::removeFirst
                val javaLast = javaDeque::removeLast
                val javaUnboundFirst = java.util.Deque<String>::removeFirst
                val javaUnboundLast = java.util.Deque<String>::removeLast
                val linkedFirst = java.util.LinkedList<String>::removeFirst
                val linkedLast = java.util.LinkedList<String>::removeLast
            }
            """.trimIndent(),
        )

        assertEquals(0, findings.size, findings.map { it.message }.toString())
    }

    @Test
    fun `accepts unrelated callable references`(env: KotlinEnvironmentContainer) {
        val findings = lint(
            env,
            """
            class CustomStack {
                fun removeFirst() = Unit
                fun removeLast() = Unit
            }

            fun removers(stack: CustomStack) {
                val first = stack::removeFirst
                val last = stack::removeLast
                val unboundFirst = CustomStack::removeFirst
                val unboundLast = CustomStack::removeLast
            }
            """.trimIndent(),
        )

        assertEquals(0, findings.size, findings.map { it.message }.toString())
    }

    @Test
    fun `reports unresolved callable references`(env: KotlinEnvironmentContainer) {
        val findings = lint(
            env,
            """
            fun remover(values: MissingType) = values::removeLast
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("Unable to verify"))
    }

    @Test
    fun `accepts Kotlin ArrayDeque operations`(env: KotlinEnvironmentContainer) {
        val findings = lint(
            env,
            """
            fun drain() {
                val deque = ArrayDeque<String>()
                deque.addLast("value")
                deque.removeFirst()
                deque.addLast("value")
                deque.removeLast()
            }
            """.trimIndent(),
        )

        assertEquals(0, findings.size, findings.map { it.message }.toString())
    }

    @Test
    fun `accepts explicit indexed removals`(env: KotlinEnvironmentContainer) {
        val findings = lint(
            env,
            """
            fun drain() {
                val values = mutableListOf("first", "last")
                values.removeAt(0)
                values.removeAt(values.lastIndex)
            }
            """.trimIndent(),
        )

        assertEquals(0, findings.size, findings.map { it.message }.toString())
    }

    @Test
    fun `ignores unrelated same-named members`(env: KotlinEnvironmentContainer) {
        val findings = lint(
            env,
            """
            class CustomStack {
                fun removeFirst() = Unit
                fun removeLast() = Unit
            }

            fun drain(stack: CustomStack) {
                stack.removeFirst()
                stack.removeLast()
            }
            """.trimIndent(),
        )

        assertEquals(0, findings.size, findings.map { it.message }.toString())
    }

    @Test
    fun `accepts Java deque operations available on old Android`(env: KotlinEnvironmentContainer) {
        val findings = lint(
            env,
            """
            fun drain() {
                val deque = java.util.LinkedList<String>()
                deque.add("first")
                deque.add("last")
                deque.removeFirst()
                deque.removeLast()
            }
            """.trimIndent(),
        )

        assertEquals(0, findings.size, findings.map { it.message }.toString())
    }

    @Test
    fun `reports calls whose receiver type cannot be resolved`(env: KotlinEnvironmentContainer) {
        val findings = lint(
            env,
            """
            fun drain(values: MissingType) {
                values.removeLast()
            }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("Unable to verify"))
    }
}
