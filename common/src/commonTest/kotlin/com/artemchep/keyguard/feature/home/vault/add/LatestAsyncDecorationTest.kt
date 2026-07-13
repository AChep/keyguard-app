package com.artemchep.keyguard.feature.home.vault.add

import com.artemchep.keyguard.common.model.Loadable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LatestAsyncDecorationTest {
    @Test
    fun `a non-cooperative decoration cannot delay a new authoritative value`() = runTest {
        val source = MutableStateFlow("old")
        val decorationStarted = CompletableDeferred<Unit>()
        val releaseDecoration = CompletableDeferred<Unit>()
        val emissions = Channel<Pair<String, Loadable<String?>>>(capacity = Channel.UNLIMITED)
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            source.withLatestAsyncDecoration<String, String> { value ->
                if (value == "old") {
                    decorationStarted.complete(Unit)
                    withContext(NonCancellable) {
                        releaseDecoration.await()
                    }
                }
                "decorated:$value"
            }.collect(emissions::send)
        }

        try {
            assertEquals("old" to Loadable.Loading, emissions.receive())
            decorationStarted.await()

            source.value = "new"

            assertEquals(
                "new" to Loadable.Loading,
                withTimeout(1_000L) { emissions.receive() },
            )
            assertFalse(releaseDecoration.isCompleted)

            releaseDecoration.complete(Unit)

            val completed = withTimeout(1_000L) {
                var emission = emissions.receive()
                while (emission.second !is Loadable.Ok) {
                    emission = emissions.receive()
                }
                emission
            }
            assertEquals(
                "new" to Loadable.Ok("decorated:new"),
                completed,
            )
        } finally {
            releaseDecoration.complete(Unit)
            collection.cancelAndJoin()
        }
    }

    @Test
    fun `a delayed successful decoration is loading until its value is available`() = runTest {
        val source = MutableStateFlow("key")
        val releaseDecoration = CompletableDeferred<Unit>()
        val emissions = Channel<Pair<String, Loadable<String?>>>(capacity = Channel.UNLIMITED)
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            source.withLatestAsyncDecoration<String, String> { value ->
                releaseDecoration.await()
                "decorated:$value"
            }.collect(emissions::send)
        }

        try {
            assertEquals("key" to Loadable.Loading, emissions.receive())

            releaseDecoration.complete(Unit)

            assertEquals(
                "key" to Loadable.Ok("decorated:key"),
                withTimeout(1_000L) { emissions.receive() },
            )
        } finally {
            releaseDecoration.complete(Unit)
            collection.cancelAndJoin()
        }
    }

    @Test
    fun `a completed null decoration is distinct from loading`() = runTest {
        val source = MutableStateFlow("key")
        val releaseDecoration = CompletableDeferred<Unit>()
        val emissions = Channel<Pair<String, Loadable<String?>>>(capacity = Channel.UNLIMITED)
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            source.withLatestAsyncDecoration<String, String> {
                releaseDecoration.await()
                null
            }.collect(emissions::send)
        }

        try {
            assertEquals("key" to Loadable.Loading, emissions.receive())

            releaseDecoration.complete(Unit)

            assertEquals(
                "key" to Loadable.Ok(null),
                withTimeout(1_000L) { emissions.receive() },
            )
        } finally {
            releaseDecoration.complete(Unit)
            collection.cancelAndJoin()
        }
    }
}
