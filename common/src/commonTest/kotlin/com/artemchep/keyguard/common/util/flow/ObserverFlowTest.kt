package com.artemchep.keyguard.common.util.flow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ObserverFlowTest {
    @Test
    fun `unregisters an observer when cancelled during registration`() = runTest {
        // A distinct dispatcher, so that `withContext(Dispatchers.Main)`
        // has to dispatch and crosses a cancellable boundary on return.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var registered = false
            var unregistered = false
            var job: Job? = null
            val flow = observerFlow<Unit> { _ ->
                registered = true
                // Cancel while still inside the registration context.
                job?.cancel()
                return@observerFlow { unregistered = true }
            }

            job = launch { flow.collect() }
            job.join()

            assertTrue(registered, "the observer is registered")
            assertTrue(unregistered, "the observer is unregistered")
        } finally {
            Dispatchers.resetMain()
        }
    }
}
