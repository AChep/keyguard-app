package com.artemchep.keyguard.android.credentialexchange

import com.artemchep.keyguard.common.service.credentialexchange.CredentialExchangeImportTransportResult
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CredentialExchangeImportTransportAndroidTest {
    @Test
    fun `a missing transfer backend degrades to unavailable`() {
        val transport = CredentialExchangeImportTransportAndroid(
            logRepository = NoopLogRepository(),
        )
        val missingBackend = assertFailsWith<CredentialExchangeBackendUnavailableException> {
            callOptionalCredentialExchangeBackend {
                throw NoClassDefFoundError(
                    "androidx/credentials/providerevents/playservices/Api",
                )
            }
        }
        val result = transport.handleFailure(missingBackend)

        val failure = assertIs<CredentialExchangeImportTransportResult.Failure>(result)
        assertEquals(
            CredentialExchangeImportTransportResult.Failure.Kind.Unavailable,
            failure.kind,
        )
    }

    @Test
    fun `cancellation is rethrown rather than mapped to a failure`() {
        val transport = CredentialExchangeImportTransportAndroid(
            logRepository = NoopLogRepository(),
        )

        assertFailsWith<CancellationException> {
            transport.handleFailure(CancellationException("screen closed"))
        }
    }

    @Test
    fun `fatal errors are rethrown rather than mapped to unavailable`() {
        val transport = CredentialExchangeImportTransportAndroid(
            logRepository = NoopLogRepository(),
        )

        assertFailsWith<AssertionError> {
            transport.handleFailure(AssertionError("broken invariant"))
        }
    }

    @Test
    fun `linkage errors outside the backend call are rethrown`() {
        val transport = CredentialExchangeImportTransportAndroid(
            logRepository = NoopLogRepository(),
        )

        assertFailsWith<NoClassDefFoundError> {
            transport.handleFailure(
                NoClassDefFoundError("com/example/UnrelatedDependency"),
            )
        }
    }
}

private class NoopLogRepository : LogRepository {
    override fun post(
        tag: String,
        message: String,
        level: LogLevel,
    ) = Unit

    override suspend fun add(
        tag: String,
        message: String,
        level: LogLevel,
    ) = Unit
}
