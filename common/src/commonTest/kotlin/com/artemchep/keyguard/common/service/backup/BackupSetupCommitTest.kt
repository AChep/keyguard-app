package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.file.FileAccessToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BackupSetupCommitTest {
    private val config = BackupConfig(
        enabled = true,
        store = BackupStoreConfig.Local("file:///backups", FileAccessToken("bookmark")),
        password = Password("encryption-password"),
        retention = BackupRetention(90),
    )

    @Test
    fun savesTheCompleteVerifiedConfigurationExactlyOnce() = runTest {
        val events = mutableListOf<String>()
        val savedConfigs = mutableListOf<BackupConfig>()
        val saved = verifyAndSaveBackupSetup(
            config = config,
            verify = {
                assertEquals(config, it)
                events += "verify"
            },
            save = {
                events += "save"
                savedConfigs += it
            },
            isCurrent = { true },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        assertTrue(saved)
        assertEquals(listOf("verify", "save"), events)
        assertEquals(listOf(config), savedConfigs)
    }

    @Test
    fun failedVerificationLeavesTheSavedConfigurationUntouched() = runTest {
        var commits = 0
        assertFailsWith<IllegalStateException> {
            verifyAndSaveBackupSetup(
                config = config,
                verify = { error("Destination unavailable") },
                save = { commits += 1 },
                isCurrent = { true },
                dispatcher = StandardTestDispatcher(testScheduler),
            )
        }
        assertEquals(0, commits)
    }

    @Test
    fun verificationFromAnOldEditorCannotSaveAfterReset() = runTest {
        val verification = CompletableDeferred<Unit>()
        var current = true
        var commits = 0
        val result = async {
            verifyAndSaveBackupSetup(
                config = config,
                verify = { verification.await() },
                save = { commits += 1 },
                isCurrent = { current },
                dispatcher = StandardTestDispatcher(testScheduler),
            )
        }
        runCurrent()
        current = false
        verification.complete(Unit)
        assertFalse(result.await())
        assertEquals(0, commits)
    }

    @Test
    fun cancellingWhileVerifyingDoesNotCommitOrReportSuccess() = runTest {
        val verification = CompletableDeferred<Unit>()
        var commits = 0
        var completed = false
        val result = async {
            completed = verifyAndSaveBackupSetup(
                config = config,
                verify = { verification.await() },
                save = { commits += 1 },
                isCurrent = { true },
                dispatcher = StandardTestDispatcher(testScheduler),
            )
        }
        runCurrent()
        result.cancelAndJoin()
        verification.complete(Unit)
        runCurrent()
        assertEquals(0, commits)
        assertFalse(completed)
    }

    @Test
    fun persistenceFailureDoesNotReportSuccessfulSetup() = runTest {
        var completed = false
        assertFailsWith<IllegalStateException> {
            completed = verifyAndSaveBackupSetup(
                config = config,
                verify = {},
                save = { error("Storage unavailable") },
                isCurrent = { true },
                dispatcher = StandardTestDispatcher(testScheduler),
            )
        }
        assertFalse(completed)
    }
}
