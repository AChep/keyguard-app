package com.artemchep.keyguard.common.service.gpgagent.impl

import com.artemchep.keyguard.common.service.gpgagent.AndroidBridgeGpgAgentRequestProcessor
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentOperation
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentRequestProcessor
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgAgentRequestTestFixture as Fixture
import com.artemchep.keyguard.common.service.sshagent.androidBridgeAuthorization
import com.artemchep.keyguard.common.service.sshagent.buildAndroidSshAgentCallerIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Android builds a processor for each Termux bridge session, all sharing one approval memory. */
@OptIn(ExperimentalCoroutinesApi::class)
class GpgAgentSharedApprovalMemoryTest {
    @Test
    fun `bridge sessions of a verified app reuse an approval`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val fixture = Fixture(this)
            fixture.succeed(operation, caller = null, processor = fixture.bridge(connection = 1, app = 40))
            fixture.succeed(operation, caller = null, processor = fixture.bridge(connection = 2, app = 40))
            assertEquals(1, fixture.approvals, operation.toString())
            assertEquals(2, fixture.crypto.calls)
        }
    }

    @Test
    fun `bridge sessions of different verified apps do not share approvals`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val fixture = Fixture(this)
            fixture.succeed(operation, caller = null, processor = fixture.bridge(connection = 1, app = 40))
            fixture.succeed(operation, caller = null, processor = fixture.bridge(connection = 2, app = 41))
            assertEquals(2, fixture.approvals, operation.toString())
        }
    }

    @Test
    fun `without a verified app an approval covers only its own bridge`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val fixture = Fixture(this)
            val first = fixture.bridge(connection = 1, app = null)
            fixture.succeed(operation, caller = null, processor = first)
            fixture.succeed(operation, caller = null, processor = first)
            assertEquals(1, fixture.approvals, operation.toString())

            fixture.succeed(operation, caller = null, processor = fixture.bridge(connection = 2, app = null))
            assertEquals(2, fixture.approvals, operation.toString())
        }
    }

    @Test
    fun `locking the vault forgets approvals of every bridge session`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val fixture = Fixture(this)
            fixture.succeed(operation, caller = null, processor = fixture.bridge(connection = 1, app = 40))

            val unlock = fixture.lock()
            runCurrent()
            unlock()
            fixture.succeed(operation, caller = null, processor = fixture.bridge(connection = 2, app = 40))
            assertEquals(2, fixture.approvals, operation.toString())
        }
    }

    /** One Termux bridge session, attributed to the Android app that sent its broadcast. */
    private fun Fixture.bridge(
        connection: Byte,
        app: Byte?,
    ): GpgAgentRequestProcessor = AndroidBridgeGpgAgentRequestProcessor(
        delegate = createProcessor(),
        senderAppInfo = buildAndroidSshAgentCallerIdentity(
            appName = "Termux",
            appBundlePath = "com.termux",
            authorization = androidBridgeAuthorization(
                principalFingerprint = app?.let { value -> ByteArray(32) { value } },
                connectionFingerprint = ByteArray(32) { connection },
            ),
        ),
    )
}
