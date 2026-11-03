package com.artemchep.keyguard.feature.auth.certificate

import com.artemchep.keyguard.provider.bitwarden.transport.MutualTlsCertificateMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class ClientCertificateStatusTest {
    private val now = Instant.parse("2026-11-02T12:00:00Z")

    @Test
    fun `status flags the renewal window and both ends of the validity period`() {
        assertEquals(ClientCertificateStatus.NOT_YET_VALID, metadata(from = 1.days, until = 400.days).status(now))
        assertEquals(ClientCertificateStatus.VALID, metadata(until = 31.days).status(now))
        assertEquals(ClientCertificateStatus.EXPIRING_SOON, metadata(until = 30.days).status(now))
        assertEquals(ClientCertificateStatus.EXPIRED, metadata(until = Duration.ZERO).status(now))
    }

    @Test
    fun `lifetime progress is the elapsed share, clamped`() {
        assertEquals(0.25f, metadata(from = (-25).days, until = 75.days).lifetimeProgress(now))
        assertEquals(0f, metadata(from = 5.days, until = 10.days).lifetimeProgress(now))
        assertEquals(1f, metadata(from = (-10).days, until = (-5).days).lifetimeProgress(now))
        assertEquals(1f, metadata(from = Duration.ZERO, until = Duration.ZERO).lifetimeProgress(now))
    }

    private fun metadata(from: Duration = (-1).days, until: Duration) = MutualTlsCertificateMetadata(
        displayName = "client",
        subject = "CN=client",
        issuer = "CN=CA",
        issuerName = "CA",
        validFrom = now + from,
        expiresAt = now + until,
        serialNumber = "01",
        sha256Fingerprint = "AB",
        keyType = "RSA 2048",
    )
}
