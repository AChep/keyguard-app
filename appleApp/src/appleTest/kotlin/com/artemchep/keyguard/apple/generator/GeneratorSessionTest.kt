package com.artemchep.keyguard.apple.generator

import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.common.model.GetPasswordResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GeneratorSessionTest {
    @Test
    fun `windows and editors isolate inputs actions results and cancellation`() {
        val first = Source()
        val second = Source()
        val firstFrames = mutableListOf<GeneratorSnapshot>()
        val secondFrames = mutableListOf<GeneratorSnapshot>()
        val results = mutableListOf<GetPasswordResult?>()
        first.session.observeWithResult({ firstFrames += it }, { results += it })
        second.session.observe { secondFrames += it }
        val invoked = mutableListOf<String>()
        first.publish(snapshot("first"), actions("first", invoked))
        second.publish(snapshot("second"), actions("second", invoked))
        first.result(GetPasswordResult.Value("first"))
        mutate(first.session)
        second.session.invokeGeneratorAction("copy")
        assertEquals(
            listOf(
                "copy first", "switch first true", "text first draft",
                "counter first 2", "length first 12", "copy second",
            ),
            invoked,
        )
        first.session.close()
        first.publish(snapshot("late"), actions("late", invoked))
        first.result(GetPasswordResult.Value("late"))
        mutate(first.session)
        second.publish(snapshot("updated"), actions("second", invoked))
        assertEquals("first", firstFrames.single().typeTitle)
        assertEquals("updated", secondFrames.last().typeTitle)
        assertEquals(listOf<GetPasswordResult?>(GetPasswordResult.Value("first")), results)
        assertEquals(1, first.cancellations)
        assertEquals(0, second.cancellations)
        second.session.close()
    }

    @Test
    fun `empty locked frames clear every action and closing rejects reopening`() {
        val source = Source()
        val invoked = mutableListOf<String>()
        val observation = source.session.observe {}
        source.publish(snapshot("open"), actions("open", invoked))
        source.publish(GeneratorSnapshot.empty, GeneratorSessionActions())
        mutate(source.session)
        assertEquals(emptyList(), invoked)
        observation.cancel()
        observation.cancel()
        assertEquals(1, source.cancellations)
        assertFailsWith<IllegalStateException> { source.session.observe {} }
    }

    private fun mutate(session: GeneratorSession) {
        session.invokeGeneratorAction("copy")
        session.setGeneratorSwitch("field", true)
        session.setGeneratorText("field", "draft")
        session.setGeneratorCounter("field", 2)
        session.setGeneratorLength(12)
    }

    private fun snapshot(title: String) = GeneratorSnapshot.empty.copy(loaded = true, typeTitle = title)
    private fun actions(owner: String, invoked: MutableList<String>) = GeneratorSessionActions(
        actions = mapOf("copy" to { invoked += "copy $owner" }),
        switches = mapOf("field" to { invoked += "switch $owner $it" }),
        text = mapOf("field" to { invoked += "text $owner $it" }),
        counters = mapOf("field" to { invoked += "counter $owner $it" }),
        length = { invoked += "length $owner $it" },
    )

    private class Source {
        lateinit var publish: (GeneratorSnapshot, GeneratorSessionActions) -> Unit
        lateinit var result: (GetPasswordResult?) -> Unit
        var cancellations = 0
        val session = GeneratorSession { publish, result ->
            this.publish = publish
            this.result = result
            KeyguardCancellable { cancellations++ }
        }
    }
}
