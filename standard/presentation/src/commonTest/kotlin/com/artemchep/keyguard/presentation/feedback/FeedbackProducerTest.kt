package com.artemchep.keyguard.presentation.feedback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackProducerTest {
    @Test
    fun `blank messages are invalid and cannot be sent or cleared`() = runTest {
        val fixture = fixture(text = "   ")

        val state = fixture.states.first()

        assertEquals(FeedbackValidationError.MUST_NOT_BE_BLANK, state.message.error)
        assertNull(state.onSendClick)
        assertNull(state.onClear)
    }

    @Test
    fun `non-space whitespace is invalid but can be cleared`() = runTest {
        val fixture = fixture(text = "\t\n")

        val state = fixture.states.first()

        assertEquals(FeedbackValidationError.MUST_NOT_BE_BLANK, state.message.error)
        assertNull(state.onSendClick)
        assertNotNull(state.onClear).invoke()
        assertEquals(listOf(""), fixture.commandWrites)
    }

    @Test
    fun `send trims spaces and emits the email effect`() = runTest {
        val fixture = fixture(text = "  useful feedback  ")

        assertNotNull(fixture.states.first().onSendClick).invoke()

        assertEquals(
            listOf<FeedbackEffect>(
                FeedbackEffect.SendEmail(
                    email = "artemchep+keyguard@gmail.com",
                    body = "useful feedback",
                ),
            ),
            fixture.effects,
        )
    }

    @Test
    fun `clear delegates one command and observes its revision update`() = runTest {
        val fixture = fixture(text = "feedback", revision = 4)

        assertNotNull(fixture.states.first().onClear).invoke()

        assertEquals(listOf(""), fixture.commandWrites)
        val clearedState = fixture.states.first()
        assertEquals("", clearedState.message.text)
        assertEquals(5, clearedState.message.revision)
        assertNull(clearedState.onClear)
    }

    @Test
    fun `restored and later upstream snapshots retain their atomic revisions`() = runTest {
        val fixture = fixture(text = "restored", revision = 1)

        val observedStates = mutableListOf<FeedbackState>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.states.take(2).toList(observedStates)
        }

        fixture.message.value = FeedbackTextState(
            id = "message",
            text = "programmatic replacement",
            revision = 7,
        )

        collector.join()

        assertEquals(
            listOf(
                "restored" to 1,
                "programmatic replacement" to 7,
            ),
            observedStates.map { it.message.text to it.message.revision },
        )
    }

    @Test
    fun `user edit delegates without changing the revision`() = runTest {
        val fixture = fixture(text = "restored", revision = 1)

        fixture.states.first().onMessageChange("restored draft")

        assertEquals(listOf("restored draft"), fixture.userWrites)
        val editedState = fixture.states.first()
        assertEquals("restored draft", editedState.message.text)
        assertEquals(1, editedState.message.revision)
    }

    @Test
    fun `send action keeps the validated snapshot that produced it`() = runTest {
        val fixture = fixture(text = "first")
        val firstSend = assertNotNull(fixture.states.first().onSendClick)

        fixture.message.update { it.copy(text = "second") }
        firstSend()
        assertNotNull(fixture.states.first().onSendClick).invoke()

        assertEquals(
            listOf<FeedbackEffect>(
                FeedbackEffect.SendEmail(
                    email = "artemchep+keyguard@gmail.com",
                    body = "first",
                ),
                FeedbackEffect.SendEmail(
                    email = "artemchep+keyguard@gmail.com",
                    body = "second",
                ),
            ),
            fixture.effects,
        )
    }

    @Test
    fun `test crash command preserves the diagnostic failure`() = runTest {
        val fixture = fixture(text = "send test crash report")

        val error = assertFailsWith<RuntimeException> {
            assertNotNull(fixture.states.first().onSendClick).invoke()
        }
        assertEquals("Test crash report.", error.message)
        assertEquals(emptyList<FeedbackEffect>(), fixture.effects)
    }

    private fun fixture(
        text: String,
        revision: Int = 0,
    ): Fixture {
        val message = MutableStateFlow(
            FeedbackTextState(
                id = "message",
                text = text,
                revision = revision,
            ),
        )
        val effects = mutableListOf<FeedbackEffect>()
        val userWrites = mutableListOf<String>()
        val commandWrites = mutableListOf<String>()
        val states = feedbackStateProducer(
            messageFlow = message,
            onMessageChange = { updatedText ->
                userWrites += updatedText
                message.update { it.copy(text = updatedText) }
            },
            onSetMessage = { updatedText ->
                commandWrites += updatedText
                message.update {
                    it.copy(
                        text = updatedText,
                        revision = it.revision + 1,
                    )
                }
            },
            onEffect = effects::add,
        )
        return Fixture(
            message = message,
            states = states,
            effects = effects,
            userWrites = userWrites,
            commandWrites = commandWrites,
        )
    }

    private data class Fixture(
        val message: MutableStateFlow<FeedbackTextState>,
        val states: Flow<FeedbackState>,
        val effects: MutableList<FeedbackEffect>,
        val userWrites: MutableList<String>,
        val commandWrites: MutableList<String>,
    )
}
