package com.artemchep.keyguard.android

import com.artemchep.keyguard.feature.auth.companion.CompanionAuthPhoneCapabilitySource
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthPhoneDiscovery
import com.artemchep.keyguard.feature.auth.companion.companionAuthPhoneDiscoveryFlow
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class CompanionAuthTransportAndroidTest {
    @Test
    fun `discovery emits loading before querying Google services`() = runTest {
        val source = FakeCompanionAuthPhoneCapabilitySource(initialReachable = false)

        assertEquals(CompanionAuthPhoneDiscovery.Loading, companionAuthPhoneDiscoveryFlow(source).first())
        assertFalse(source.didQuery)
    }

    @Test
    fun `unavailable wearable API becomes discovery state`() = runTest {
        val source = FakeCompanionAuthPhoneCapabilitySource(
            initialReachable = false,
            initialError = unavailableApi(),
        )

        assertEquals(
            listOf(CompanionAuthPhoneDiscovery.Loading, CompanionAuthPhoneDiscovery.Unavailable),
            companionAuthPhoneDiscoveryFlow(source).toList(),
        )
    }

    @Test
    fun `listener registration failure clears previously discovered devices`() = runTest {
        val source = FakeCompanionAuthPhoneCapabilitySource(
            initialReachable = true,
            listenerError = unavailableApi(),
        )

        val values = companionAuthPhoneDiscoveryFlow(source).toList()

        assertEquals(3, values.size)
        assertEquals("node-id", values[1].devices.single().id)
        assertEquals(CompanionAuthPhoneDiscovery.Unavailable, values.last())
        assertEquals(emptyList(), values.last().devices)
    }

    @Test
    fun `recollecting discovery retries after services recover`() = runTest {
        val source = FakeCompanionAuthPhoneCapabilitySource(
            initialReachable = true,
            initialError = unavailableApi(),
        )
        val discovery = companionAuthPhoneDiscoveryFlow(source)
        assertEquals(CompanionAuthPhoneDiscovery.Unavailable, discovery.toList().last())

        source.initialError = null
        val recovered = discovery.filterIsInstance<CompanionAuthPhoneDiscovery.Ready>().first()

        assertEquals("node-id", recovered.devices.single().id)
    }

    @Test
    fun `discovery preserves cancellation`() = runTest {
        val cancellation = CancellationException("Screen closed")
        val source = FakeCompanionAuthPhoneCapabilitySource(
            initialReachable = false,
            initialError = cancellation,
        )

        val thrown = assertFailsWith<CancellationException> {
            companionAuthPhoneDiscoveryFlow(source).toList()
        }
        assertSame(cancellation, thrown)
    }

    @Test
    fun `discovery does not hide unexpected failures`() = runTest {
        val failure = IllegalStateException("Unexpected failure")
        val source = FakeCompanionAuthPhoneCapabilitySource(
            initialReachable = false,
            initialError = failure,
        )

        val thrown = assertFailsWith<IllegalStateException> {
            companionAuthPhoneDiscoveryFlow(source).toList()
        }
        assertSame(failure, thrown)
    }

    private fun unavailableApi() = ApiException(Status(CommonStatusCodes.API_NOT_CONNECTED))

    @Test
    fun `availability flow emits initial reachable snapshot`() = runTest {
        val source = FakeCompanionAuthPhoneCapabilitySource(
            initialReachable = true,
        )

        val values = companionAuthPhoneDiscoveryFlow(source)
            .filterIsInstance<CompanionAuthPhoneDiscovery.Ready>()
            .map { it.devices.isNotEmpty() }
            .take(1)
            .toList()

        assertEquals(listOf(true), values)
    }

    @Test
    fun `availability flow emits reachable to unreachable transition`() = runTest {
        val source = FakeCompanionAuthPhoneCapabilitySource(
            initialReachable = true,
        )

        val valuesDeferred = async {
            companionAuthPhoneDiscoveryFlow(source)
                .filterIsInstance<CompanionAuthPhoneDiscovery.Ready>()
                .map { it.devices.isNotEmpty() }
                .take(2)
                .toList()
        }
        runCurrent()
        source.emitAvailability(false)

        assertEquals(listOf(true, false), valuesDeferred.await())
    }

    @Test
    fun `availability flow suppresses duplicate updates`() = runTest {
        val source = FakeCompanionAuthPhoneCapabilitySource(
            initialReachable = false,
        )

        val valuesDeferred = async {
            companionAuthPhoneDiscoveryFlow(source)
                .filterIsInstance<CompanionAuthPhoneDiscovery.Ready>()
                .map { it.devices.isNotEmpty() }
                .take(2)
                .toList()
        }
        runCurrent()
        source.emitAvailability(false)
        source.emitAvailability(true)

        assertEquals(listOf(false, true), valuesDeferred.await())
    }
}

private class FakeCompanionAuthPhoneCapabilitySource(
    private val initialReachable: Boolean,
    var initialError: Throwable? = null,
    private val listenerError: Throwable? = null,
) : CompanionAuthPhoneCapabilitySource {
    private val updates = MutableSharedFlow<Boolean>(
        extraBufferCapacity = 4,
    )

    var didQuery = false
        private set

    override suspend fun getReachablePhoneNodes(): List<com.google.android.gms.wearable.Node> {
        didQuery = true
        initialError?.let { throw it }
        return if (initialReachable) {
            listOf(FakeNode)
        } else {
            emptyList()
        }
    }

    override fun observeReachablePhoneNodes(): Flow<List<com.google.android.gms.wearable.Node>> =
        flow {
            listenerError?.let { throw it }
            updates.collect { isReachable ->
                emit(if (isReachable) listOf(FakeNode) else emptyList())
            }
        }

    fun emitAvailability(
        isReachable: Boolean,
    ) {
        updates.tryEmit(isReachable)
    }
}

private object FakeNode : com.google.android.gms.wearable.Node {
    override fun getDisplayName(): String = "node"

    override fun getId(): String = "node-id"

    override fun isNearby(): Boolean = true
}
