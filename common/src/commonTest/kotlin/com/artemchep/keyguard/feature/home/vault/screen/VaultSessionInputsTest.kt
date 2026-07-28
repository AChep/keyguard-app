package com.artemchep.keyguard.feature.home.vault.screen

import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetConcealFields
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VaultSessionInputsTest {
    @Test
    fun `construction invokes every use case exactly once`() = runTest {
        val sources = Sources()
        val inputs = createInputs(sources)

        sources.assertInvokedExactlyOnce()

        inputs.close()
    }

    @Test
    fun `critical sources start eagerly while collections remain demand driven`() = runTest {
        val sources = Sources()
        val inputs = createInputs(sources)
        runCurrent()

        assertEquals(1, sources.ciphers.collectionStarts)
        assertEquals(1, sources.profiles.collectionStarts)
        assertEquals(1, sources.organizations.collectionStarts)
        assertEquals(1, sources.accounts.collectionStarts)
        assertEquals(1, sources.canWrite.collectionStarts)
        assertEquals(1, sources.concealFields.collectionStarts)
        assertEquals(1, sources.appIcons.collectionStarts)
        assertEquals(1, sources.websiteIcons.collectionStarts)
        assertEquals(0, sources.collections.collectionStarts)

        inputs.close()
    }

    @Test
    fun `conceal fields has no value until its repository source emits`() = runTest {
        val sources = Sources()
        val inputs = createInputs(sources)
        runCurrent()

        assertEquals(emptyList(), inputs.concealFields.replayCache)

        sources.concealFields.emit(true)
        runCurrent()

        assertEquals(listOf(true), inputs.concealFields.replayCache)

        inputs.close()
    }

    @Test
    fun `write capability remains unknown until repository permission emits`() = runTest {
        val sources = Sources()
        val inputs = createInputs(sources)
        runCurrent()

        assertEquals(WriteCapability.Unknown, inputs.canWrite.value)

        sources.canWrite.emit(true)
        runCurrent()
        assertEquals(WriteCapability.Allowed, inputs.canWrite.value)

        sources.canWrite.emit(false)
        runCurrent()
        assertEquals(WriteCapability.Denied, inputs.canWrite.value)

        inputs.close()
    }

    @Test
    fun `multiple consumers share one active upstream collection`() = runTest {
        val sources = Sources()
        val inputs = createInputs(sources)
        runCurrent()

        assertEquals(1, sources.ciphers.collectionStarts)

        val firstCipherCollector = backgroundScope.launch(
            UnconfinedTestDispatcher(testScheduler),
        ) {
            inputs.ciphers.collect()
        }
        val secondCipherCollector = backgroundScope.launch(
            UnconfinedTestDispatcher(testScheduler),
        ) {
            inputs.ciphers.collect()
        }
        runCurrent()

        assertEquals(1, sources.ciphers.collectionStarts)

        val firstCollectionCollector = backgroundScope.launch(
            UnconfinedTestDispatcher(testScheduler),
        ) {
            inputs.collections.collect()
        }
        val secondCollectionCollector = backgroundScope.launch(
            UnconfinedTestDispatcher(testScheduler),
        ) {
            inputs.collections.collect()
        }
        runCurrent()

        assertEquals(1, sources.collections.collectionStarts)
        assertEquals(1, sources.collections.activeCollections)

        firstCipherCollector.cancel()
        secondCipherCollector.cancel()
        firstCollectionCollector.cancel()
        secondCollectionCollector.cancel()
        inputs.close()
    }

    @Test
    fun `close cancels session collections without cancelling caller scope`() = runTest {
        val sources = Sources()
        val inputs = createInputs(sources)
        val collectionCollector = backgroundScope.launch(
            UnconfinedTestDispatcher(testScheduler),
        ) {
            inputs.collections.collect()
        }
        runCurrent()

        assertEquals(1, sources.ciphers.activeCollections)
        assertEquals(1, sources.collections.activeCollections)

        inputs.close()
        inputs.close()
        runCurrent()

        assertEquals(0, sources.ciphers.activeCollections)
        assertEquals(0, sources.collections.activeCollections)
        assertTrue(backgroundScope.coroutineContext[Job]?.isActive == true)

        collectionCollector.cancel()
    }

    private fun TestScope.createInputs(
        sources: Sources,
    ) = VaultSessionInputs(
        scope = backgroundScope,
        getCiphers = object : GetCiphers {
            override fun invoke(): Flow<List<DSecret>> = sources.ciphers.invoke()
        },
        getProfiles = object : GetProfiles {
            override fun invoke(): Flow<List<DProfile>> = sources.profiles.invoke()
        },
        getOrganizations = object : GetOrganizations {
            override fun invoke(): Flow<List<DOrganization>> = sources.organizations.invoke()
        },
        getCollections = object : GetCollections {
            override fun invoke(): Flow<List<DCollection>> = sources.collections.invoke()
        },
        getAccounts = object : GetAccounts {
            override fun invoke(): Flow<List<DAccount>> = sources.accounts.invoke()
        },
        getCanWrite = object : GetCanWrite {
            override fun invoke(): Flow<Boolean> = sources.canWrite.invoke()
        },
        getConcealFields = object : GetConcealFields {
            override fun invoke(): Flow<Boolean> = sources.concealFields.invoke()
        },
        getAppIcons = object : GetAppIcons {
            override fun invoke(): Flow<Boolean> = sources.appIcons.invoke()
        },
        getWebsiteIcons = object : GetWebsiteIcons {
            override fun invoke(): Flow<Boolean> = sources.websiteIcons.invoke()
        },
    )

    private class Sources {
        val ciphers = RecordingSource<List<DSecret>>()
        val profiles = RecordingSource<List<DProfile>>()
        val organizations = RecordingSource<List<DOrganization>>()
        val collections = RecordingSource<List<DCollection>>()
        val accounts = RecordingSource<List<DAccount>>()
        val canWrite = RecordingSource<Boolean>()
        val concealFields = RecordingSource<Boolean>()
        val appIcons = RecordingSource<Boolean>()
        val websiteIcons = RecordingSource<Boolean>()

        fun assertInvokedExactlyOnce() {
            val invocationCounts = listOf(
                ciphers.invocations,
                profiles.invocations,
                organizations.invocations,
                collections.invocations,
                accounts.invocations,
                canWrite.invocations,
                concealFields.invocations,
                appIcons.invocations,
                websiteIcons.invocations,
            )
            assertEquals(
                expected = List(invocationCounts.size) { 1 },
                actual = invocationCounts,
            )
        }
    }

    private class RecordingSource<T> {
        private val values = MutableSharedFlow<T>()

        var invocations = 0
            private set
        var collectionStarts = 0
            private set
        var activeCollections = 0
            private set

        operator fun invoke(): Flow<T> {
            invocations += 1
            return flow {
                collectionStarts += 1
                activeCollections += 1
                try {
                    values.collect { value ->
                        emit(value)
                    }
                } finally {
                    activeCollections -= 1
                }
            }
        }

        suspend fun emit(value: T) {
            values.emit(value)
        }
    }
}
