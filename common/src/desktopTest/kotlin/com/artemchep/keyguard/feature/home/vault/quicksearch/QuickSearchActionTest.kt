package com.artemchep.keyguard.feature.home.vault.quicksearch

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.common.exception.OtpCodeGenerationException
import com.artemchep.keyguard.feature.navigation.NavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class QuickSearchActionTest {
    @Test
    fun `copy primary copies the primary value and finishes`() = runTest {
        val clipboard = RecordingClipboardService()
        val item = createVaultItem(
            id = "login",
            secret = createSecret(
                id = "login",
                login = DSecret.Login(
                    username = "person@example.com",
                ),
            ),
        ).copy(
            copyText = createCopyText(clipboard),
        )
        val controller = RecordingNavigationController(this)
        var finishedCalls = 0

        performQuickSearchAction(
            actionType = QuickSearchActionType.CopyPrimary,
            item = item,
            controller = controller,
            getTotpCode = EmptyGetTotpCode,
            scope = this,
            onFinished = { finishedCalls += 1 },
        )

        assertEquals("person@example.com", clipboard.value)
        assertEquals(false, clipboard.concealed)
        assertEquals(1, finishedCalls)
        assertNull(controller.intents.firstOrNull())
    }

    @Test
    fun `copy secret copies the secret value and marks it concealed`() = runTest {
        val clipboard = RecordingClipboardService()
        val item = createVaultItem(
            id = "login",
            secret = createSecret(
                id = "login",
                login = DSecret.Login(
                    password = "hunter2",
                ),
            ),
        ).copy(
            copyText = createCopyText(clipboard),
        )
        var finishedCalls = 0

        performQuickSearchAction(
            actionType = QuickSearchActionType.CopySecret,
            item = item,
            controller = RecordingNavigationController(this),
            getTotpCode = EmptyGetTotpCode,
            scope = this,
            onFinished = { finishedCalls += 1 },
        )

        assertEquals("hunter2", clipboard.value)
        assertEquals(true, clipboard.concealed)
        assertEquals(1, finishedCalls)
    }

    @Test
    fun `copy otp fetches the current code and finishes after copy`() = runTest {
        val clipboard = RecordingClipboardService()
        val item = createVaultItem(
            id = "otp",
            secret = createSecret(
                id = "otp",
                login = DSecret.Login(
                    username = "person@example.com",
                    totp = createTotp(),
                ),
            ),
        ).copy(
            copyText = createCopyText(clipboard),
        )
        var finishedCalls = 0

        performQuickSearchAction(
            actionType = QuickSearchActionType.CopyOtp,
            item = item,
            controller = RecordingNavigationController(this),
            getTotpCode = successTotpCode("123456"),
            scope = this,
            onFinished = { finishedCalls += 1 },
        )

        advanceUntilIdle()

        assertEquals("123456", clipboard.value)
        assertEquals(false, clipboard.concealed)
        assertEquals(1, finishedCalls)
    }

    @Test
    fun `copy otp is a no op when code generation fails`() = runTest {
        val clipboard = RecordingClipboardService()
        val item = createVaultItem(
            id = "otp",
            secret = createSecret(
                id = "otp",
                login = DSecret.Login(
                    totp = createTotp(),
                ),
            ),
        ).copy(
            copyText = createCopyText(clipboard),
        )
        var finishedCalls = 0

        performQuickSearchAction(
            actionType = QuickSearchActionType.CopyOtp,
            item = item,
            controller = RecordingNavigationController(this),
            getTotpCode = object : GetTotpCode {
                override fun invoke(p1: TotpToken): Flow<Either<Throwable, TotpCode>> =
                    flowOf(OtpCodeGenerationException().left())
            },
            scope = this,
            onFinished = { finishedCalls += 1 },
        )

        advanceUntilIdle()

        assertNull(clipboard.value)
        assertEquals(0, finishedCalls)
    }

    @Test
    fun `open in browser queues navigation intent and finishes`() = runTest {
        val controller = RecordingNavigationController(this)
        var finishedCalls = 0

        performQuickSearchAction(
            actionType = QuickSearchActionType.OpenInBrowser,
            item = createVaultItem(
                id = "login",
                secret = createSecret(
                    id = "login",
                    uris = listOf(
                        DSecret.Uri(uri = "https://example.com"),
                    ),
                ),
            ),
            controller = controller,
            getTotpCode = EmptyGetTotpCode,
            scope = this,
            onFinished = { finishedCalls += 1 },
        )

        val intent = assertIs<NavigationIntent.NavigateToBrowser>(controller.intents.single())
        assertEquals("https://example.com", intent.url)
        assertEquals(1, finishedCalls)
    }
}

private data object EmptyGetTotpCode : GetTotpCode {
    override fun invoke(p1: TotpToken): Flow<Either<Throwable, TotpCode>> = emptyFlow()
}

private fun successTotpCode(code: String): GetTotpCode = object : GetTotpCode {
    override fun invoke(p1: TotpToken): Flow<Either<Throwable, TotpCode>> = flowOf(
        TotpCode(
            code = code,
            counter = TotpCode.TimeBasedCounter(
                timestamp = TEST_INSTANT,
                expiration = TEST_INSTANT + 30.seconds,
                duration = 30.seconds,
            ),
        ).right(),
    )
}

private class RecordingClipboardService : ClipboardService {
    var value: String? = null
    var concealed: Boolean? = null

    override fun setPrimaryClip(value: String, concealed: Boolean) {
        this.value = value
        this.concealed = concealed
    }

    override fun clearPrimaryClip() = Unit

    override fun hasCopyNotification(): Boolean = true
}

private class RecordingNavigationController(
    override val scope: CoroutineScope,
) : NavigationController {
    val intents = mutableListOf<NavigationIntent>()

    override fun queue(intent: NavigationIntent) {
        intents += intent
    }

    override fun canPop() = flowOf(false)
}
