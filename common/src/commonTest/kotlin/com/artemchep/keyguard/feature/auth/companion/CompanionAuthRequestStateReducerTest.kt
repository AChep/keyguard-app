package com.artemchep.keyguard.feature.auth.companion

import kotlin.test.Test
import kotlin.test.assertEquals

class CompanionAuthRequestStateReducerTest {
    @Test
    fun `waiting request can progress through import to success`() {
        val waiting = reduceCompanionAuthRequestState(
            current = null,
            event = CompanionAuthRequestEvent.RequestStarted,
        )
        val importing = reduceCompanionAuthRequestState(
            current = waiting,
            event = CompanionAuthRequestEvent.ImportStarted,
        )
        val success = reduceCompanionAuthRequestState(
            current = importing,
            event = CompanionAuthRequestEvent.ImportSucceeded,
        )

        assertEquals(CompanionAuthRequestState.WaitingForPhone, waiting)
        assertEquals(CompanionAuthRequestState.Importing, importing)
        assertEquals(CompanionAuthRequestState.Success, success)
    }

    @Test
    fun `stale started event does not overwrite launch failure`() {
        val failed = reduceCompanionAuthRequestState(
            current = CompanionAuthRequestState.WaitingForPhone,
            event = CompanionAuthRequestEvent.RemoteLaunchFailed(
                message = "boom",
            ),
        )
        val reduced = reduceCompanionAuthRequestState(
            current = failed,
            event = CompanionAuthRequestEvent.RequestStarted,
        )

        assertEquals(
            CompanionAuthRequestState.Failed(
                error = CompanionAuthError.LAUNCH_FAILED,
                message = "boom",
            ),
            reduced,
        )
    }

    @Test
    fun `stale started event does not overwrite timeout failure`() {
        val failed = reduceCompanionAuthRequestState(
            current = CompanionAuthRequestState.WaitingForPhone,
            event = CompanionAuthRequestEvent.TimedOut(
                message = "timeout",
            ),
        )
        val reduced = reduceCompanionAuthRequestState(
            current = failed,
            event = CompanionAuthRequestEvent.RequestStarted,
        )

        assertEquals(
            CompanionAuthRequestState.Failed(
                error = CompanionAuthError.TIMEOUT,
                message = "timeout",
            ),
            reduced,
        )
    }

    @Test
    fun `stale started event does not overwrite cancellation`() {
        val cancelled = reduceCompanionAuthRequestState(
            current = CompanionAuthRequestState.WaitingForPhone,
            event = CompanionAuthRequestEvent.PhoneCancelled(
                message = "cancelled",
            ),
        )
        val reduced = reduceCompanionAuthRequestState(
            current = cancelled,
            event = CompanionAuthRequestEvent.RequestStarted,
        )

        assertEquals(
            CompanionAuthRequestState.Cancelled(
                message = "cancelled",
            ),
            reduced,
        )
    }

    @Test
    fun `phone error transitions request to failure`() {
        val failed = reduceCompanionAuthRequestState(
            current = CompanionAuthRequestState.WaitingForPhone,
            event = CompanionAuthRequestEvent.PhoneErrored(
                error = CompanionAuthError.REQUEST_FAILED,
                message = "network error",
            ),
        )

        assertEquals(
            CompanionAuthRequestState.Failed(
                error = CompanionAuthError.REQUEST_FAILED,
                message = "network error",
            ),
            failed,
        )
    }

    @Test
    fun `import failure transitions request to failure`() {
        val failed = reduceCompanionAuthRequestState(
            current = CompanionAuthRequestState.Importing,
            event = CompanionAuthRequestEvent.ImportFailed(
                message = "import failed",
            ),
        )

        assertEquals(
            CompanionAuthRequestState.Failed(
                error = CompanionAuthError.IMPORT_FAILED,
                message = "import failed",
            ),
            failed,
        )
    }

    @Test
    fun `terminal success ignores later failures`() {
        val reduced = reduceCompanionAuthRequestState(
            current = CompanionAuthRequestState.Success,
            event = CompanionAuthRequestEvent.ImportFailed(
                message = "ignored",
            ),
        )

        assertEquals(CompanionAuthRequestState.Success, reduced)
    }
}
