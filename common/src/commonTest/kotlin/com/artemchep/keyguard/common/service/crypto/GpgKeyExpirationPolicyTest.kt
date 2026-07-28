package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.GpgKeyExpiry
import com.artemchep.keyguard.feature.gpgkey.GpgKeyExpiryPreset
import com.artemchep.keyguard.feature.gpgkey.gpgKeyExpirationDateRange
import com.artemchep.keyguard.feature.gpgkey.normalizeGpgKeyCustomExpiryDate
import com.artemchep.keyguard.feature.gpgkey.expiration.GpgKeyExpirationEvaluation
import com.artemchep.keyguard.feature.gpgkey.expiration.GpgKeyExpirationSelectionError
import com.artemchep.keyguard.feature.gpgkey.expiration.defaultGpgKeyExpirationComponents
import com.artemchep.keyguard.feature.gpgkey.expiration.defaultGpgKeyExpirationPreset
import com.artemchep.keyguard.feature.gpgkey.expiration.evaluateGpgKeyExpirationSelection
import com.artemchep.keyguard.feature.gpgkey.expiration.gpgKeyExpirationSelectionDateRange
import com.artemchep.keyguard.feature.gpgkey.expiration.validateGpgKeyExpirationChange
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class GpgKeyExpirationPolicyTest {
    @Test
    fun `protocol ceiling is the largest unsigned OpenPGP timestamp`() {
        assertEquals(
            expected = Instant.parse("2106-02-07T06:28:15Z"),
            actual = GPG_KEY_EXPIRATION_MAX_INSTANT,
        )
    }

    @Test
    fun `expiration date is capped at safe OpenPGP limit`() {
        assertEquals(
            expected = GPG_KEY_EXPIRATION_MAX_DATE,
            actual = clampGpgKeyExpirationDate(LocalDate(2126, 1, 1)),
        )
    }

    @Test
    fun `expiration date before limit is unchanged`() {
        val date = LocalDate(2031, 4, 15)

        assertEquals(
            expected = date,
            actual = clampGpgKeyExpirationDate(date),
        )
    }

    @Test
    fun `expiration range caps requested maximum`() {
        assertEquals(
            expected = LocalDate(2026, 7, 12)..GPG_KEY_EXPIRATION_MAX_DATE,
            actual =
                gpgKeyExpirationDateRange(
                    currentDate = LocalDate(2026, 7, 11),
                    maximumDate = LocalDate(2126, 7, 11),
                ),
        )
    }

    @Test
    fun `expiration range is absent when safe maximum is not in the future`() {
        assertNull(
            gpgKeyExpirationDateRange(
                currentDate = GPG_KEY_EXPIRATION_MAX_DATE,
            ),
        )
    }

    @Test
    fun `safe calendar limit fits protocol ceiling in extreme time zones`() {
        listOf(
            TimeZone.of("-12:00"),
            TimeZone.of("+14:00"),
        ).forEach { timeZone ->
            val expiration = gpgKeyExpirationAtEndOfDay(GPG_KEY_EXPIRATION_MAX_DATE, timeZone)
            assertTrue(
                expiration <= GPG_KEY_EXPIRATION_MAX_INSTANT,
                "Expected $timeZone to keep the safe calendar limit representable.",
            )
        }
    }

    @Test
    fun `day after safe calendar limit can exceed protocol ceiling`() {
        val date = GPG_KEY_EXPIRATION_MAX_DATE.plus(1, DateTimeUnit.DAY)

        assertTrue(
            endOfDay(date, TimeZone.of("-12:00")) > GPG_KEY_EXPIRATION_MAX_INSTANT,
        )
    }

    @Test
    fun `calendar year policy resolves once to the end of the anniversary date`() {
        assertEquals(
            expected = Instant.parse("2025-02-28T23:59:00Z"),
            actual = GpgKeyExpiry.AfterYears(1).resolve(
                creationTime = Instant.parse("2024-02-29T12:34:56Z"),
                timeZone = TimeZone.UTC,
            ),
        )
    }

    @Test
    fun `absolute date policy resolves to the end of the selected local date`() {
        assertEquals(
            expected = Instant.parse("2029-12-31T09:59:00Z"),
            actual = GpgKeyExpiry.OnDate(LocalDate(2029, 12, 31)).resolve(
                creationTime = Instant.parse("2026-07-12T00:00:00Z"),
                timeZone = TimeZone.of("+14:00"),
            ),
        )
    }

    @Test
    fun `instant and never policies preserve their direct meaning`() {
        val target = Instant.parse("2031-04-15T10:20:30Z")

        assertEquals(
            target,
            GpgKeyExpiry.At(target).resolve(
                creationTime = Instant.parse("2026-07-12T00:00:00Z"),
                timeZone = TimeZone.UTC,
            ),
        )
        assertNull(
            GpgKeyExpiry.Never.resolve(
                creationTime = Instant.parse("2026-07-12T00:00:00Z"),
                timeZone = TimeZone.UTC,
            ),
        )
    }

    @Test
    fun `calendar policies reject dates beyond the supported range`() {
        assertFailsWith<IllegalArgumentException> {
            GpgKeyExpiry.OnDate(GPG_KEY_EXPIRATION_MAX_DATE.plus(1, DateTimeUnit.DAY)).resolve(
                creationTime = Instant.parse("2026-07-12T00:00:00Z"),
                timeZone = TimeZone.UTC,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GpgKeyExpiry.AfterYears(1).resolve(
                creationTime = Instant.parse("2106-01-01T00:00:00Z"),
                timeZone = TimeZone.UTC,
            )
        }
    }

    @Test
    fun `preset identities map to one shared policy model`() {
        val customDate = LocalDate(2030, 6, 1)

        assertEquals(GpgKeyExpiry.AfterYears(1), GpgKeyExpiryPreset.OneYear.toPolicy())
        assertEquals(GpgKeyExpiry.AfterYears(2), GpgKeyExpiryPreset.TwoYears.toPolicy())
        assertEquals(GpgKeyExpiry.AfterYears(5), GpgKeyExpiryPreset.FiveYears.toPolicy())
        assertEquals(GpgKeyExpiry.Never, GpgKeyExpiryPreset.Never.toPolicy())
        assertEquals(GpgKeyExpiry.OnDate(customDate), GpgKeyExpiryPreset.Custom.toPolicy(customDate))
        assertNull(GpgKeyExpiryPreset.Custom.toPolicy())
        assertEquals(GpgKeyExpiryPreset.FiveYears, GpgKeyExpiryPreset.getOrDefault("unknown"))
    }

    @Test
    fun `renewal defaults preserve an unlimited primary`() {
        assertEquals(
            GpgKeyExpiryPreset.Never,
            defaultGpgKeyExpirationPreset(
                keyInfo(expiresAt = null, subKeys = emptyList()),
            ),
        )
        assertEquals(
            GpgKeyExpiryPreset.FiveYears,
            defaultGpgKeyExpirationPreset(
                keyInfo(
                    expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
                    subKeys = emptyList(),
                ),
            ),
        )
    }

    @Test
    fun `custom date normalization accepts only selectable future dates`() {
        val currentDate = LocalDate(2026, 7, 12)

        assertEquals(
            LocalDate(2030, 1, 2),
            normalizeGpgKeyCustomExpiryDate("2030-01-02", currentDate),
        )
        listOf("not-a-date", "2026-07-12", "2020-01-01").forEach { rawDate ->
            assertEquals(
                LocalDate(2031, 7, 12),
                normalizeGpgKeyCustomExpiryDate(rawDate, currentDate),
            )
        }
    }

    @Test
    fun `default selection includes subkeys only when every non-revoked expiry aligns`() {
        val primaryExpiry = Instant.parse("2030-01-01T00:00:00Z")
        val keyInfo = keyInfo(
            expiresAt = primaryExpiry,
            subKeys = listOf(
                subKey("aligned", expiresAt = primaryExpiry + 1_800.seconds),
                subKey("revoked", expiresAt = primaryExpiry, revoked = true),
            ),
        )

        assertEquals(
            setOf("PRIMARY", "aligned"),
            defaultGpgKeyExpirationComponents(keyInfo),
        )
    }

    @Test
    fun `one non-aligned subkey makes the default selection primary-only`() {
        val primaryExpiry = Instant.parse("2030-01-01T00:00:00Z")
        val keyInfo = keyInfo(
            expiresAt = primaryExpiry,
            subKeys = listOf(
                subKey("aligned", expiresAt = primaryExpiry + 1_800.seconds),
                subKey("different", expiresAt = primaryExpiry + 7_200.seconds),
            ),
        )

        assertEquals(
            setOf("PRIMARY"),
            defaultGpgKeyExpirationComponents(keyInfo),
        )
    }

    @Test
    fun `unlimited aligned subkeys remain unselected by default`() {
        val keyInfo = keyInfo(
            expiresAt = null,
            subKeys = listOf(subKey("SUB", expiresAt = null)),
        )

        assertEquals(
            setOf("PRIMARY"),
            defaultGpgKeyExpirationComponents(keyInfo),
        )
    }

    @Test
    fun `selection validation protects empty requests and an unselected primary`() {
        val primaryExpiry = Instant.parse("2030-01-01T00:00:00Z")
        val keyInfo = keyInfo(
            expiresAt = primaryExpiry,
            subKeys = listOf(subKey("SUB", expiresAt = primaryExpiry)),
        )

        assertEquals(
            GpgKeyExpirationSelectionError.NoComponents,
            validateGpgKeyExpirationChange(
                keyInfo,
                GpgKeyExpirationChange(expiresAt = primaryExpiry, componentFingerprints = emptySet()),
            ),
        )
        assertEquals(
            GpgKeyExpirationSelectionError.AfterPrimary,
            validateGpgKeyExpirationChange(
                keyInfo,
                GpgKeyExpirationChange(
                    expiresAt = primaryExpiry + 1.seconds,
                    componentFingerprints = setOf("SUB"),
                ),
            ),
        )
        assertNull(
            validateGpgKeyExpirationChange(
                keyInfo,
                GpgKeyExpirationChange(
                    expiresAt = primaryExpiry + 1.seconds,
                    componentFingerprints = setOf("primary", "SUB"),
                ),
            ),
        )
    }

    @Test
    fun `selection evaluator rejects a revoked primary even when it is not selected`() {
        val keyInfo = keyInfo(
            expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
            subKeys = listOf(
                subKey(
                    fingerprint = "SUB",
                    expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
                ),
            ),
            revoked = true,
        )

        assertEquals(
            GpgKeyExpirationEvaluation.Invalid(
                GpgKeyExpirationSelectionError.RevokedPrimary,
            ),
            evaluateGpgKeyExpirationSelection(
                keyInfo = keyInfo,
                preset = GpgKeyExpiryPreset.OneYear,
                componentFingerprints = setOf("SUB"),
                customDate = null,
                now = Instant.parse("2026-07-12T12:00:00Z"),
                timeZone = TimeZone.UTC,
            ),
        )
    }

    @Test
    fun `selection date range is capped by an unselected primary`() {
        val primaryExpiry = Instant.parse("2028-03-15T23:59:00Z")
        val keyInfo = keyInfo(
            expiresAt = primaryExpiry,
            subKeys = listOf(
                subKey(
                    fingerprint = "SUB",
                    createdAt = Instant.parse("2025-01-01T00:00:00Z"),
                    expiresAt = primaryExpiry,
                ),
            ),
        )

        assertEquals(
            LocalDate(2026, 7, 13)..LocalDate(2028, 3, 15),
            gpgKeyExpirationSelectionDateRange(
                keyInfo = keyInfo,
                componentFingerprints = setOf("SUB"),
                now = Instant.parse("2026-07-12T12:00:00Z"),
                timeZone = TimeZone.UTC,
            ),
        )
        assertNull(
            gpgKeyExpirationSelectionDateRange(
                keyInfo = keyInfo,
                componentFingerprints = emptySet(),
                now = Instant.parse("2026-07-12T12:00:00Z"),
                timeZone = TimeZone.UTC,
            ),
        )
    }

    @Test
    fun `selection evaluator caps a same-day subkey expiry to the primary instant`() {
        val primaryExpiry = Instant.parse("2028-03-15T12:00:00Z")
        val keyInfo = keyInfo(
            expiresAt = primaryExpiry,
            subKeys = listOf(subKey("SUB", expiresAt = primaryExpiry)),
        )

        val evaluation = evaluateGpgKeyExpirationSelection(
            keyInfo = keyInfo,
            preset = GpgKeyExpiryPreset.Custom,
            componentFingerprints = setOf("SUB"),
            customDate = LocalDate(2028, 3, 15),
            now = Instant.parse("2026-07-12T12:00:00Z"),
            timeZone = TimeZone.UTC,
        )

        assertEquals(
            GpgKeyExpirationEvaluation.Valid(
                GpgKeyExpirationChange(
                    expiresAt = primaryExpiry,
                    componentFingerprints = setOf("SUB"),
                ),
            ),
            evaluation,
        )
    }

    @Test
    fun `selection evaluator reports invalid and after-primary choices precisely`() {
        val primaryExpiry = Instant.parse("2028-03-15T12:00:00Z")
        val keyInfo = keyInfo(
            expiresAt = primaryExpiry,
            subKeys = listOf(subKey("SUB", expiresAt = primaryExpiry)),
        )

        assertEquals(
            GpgKeyExpirationEvaluation.Invalid(
                GpgKeyExpirationSelectionError.InvalidExpiration,
            ),
            evaluateGpgKeyExpirationSelection(
                keyInfo = keyInfo,
                preset = GpgKeyExpiryPreset.Custom,
                componentFingerprints = setOf("SUB"),
                customDate = LocalDate(2026, 7, 12),
                now = Instant.parse("2026-07-12T12:00:00Z"),
                timeZone = TimeZone.UTC,
            ),
        )
        assertEquals(
            GpgKeyExpirationEvaluation.Invalid(
                GpgKeyExpirationSelectionError.AfterPrimary,
            ),
            evaluateGpgKeyExpirationSelection(
                keyInfo = keyInfo,
                preset = GpgKeyExpiryPreset.FiveYears,
                componentFingerprints = setOf("SUB"),
                customDate = null,
                now = Instant.parse("2026-07-12T12:00:00Z"),
                timeZone = TimeZone.UTC,
            ),
        )
    }

    // Deliberately unclamped, unlike the production gpgKeyExpirationAtEndOfDay:
    // this exercises the raw end-of-day conversion to justify the safe calendar
    // limit — the day after the limit must be able to exceed the protocol ceiling.
    private fun endOfDay(
        date: LocalDate,
        timeZone: TimeZone,
    ): Instant =
        date
            .plus(1, DateTimeUnit.DAY)
            .atStartOfDayIn(timeZone) - 60.seconds

    private fun keyInfo(
        expiresAt: Instant?,
        subKeys: List<GpgPublicSubKeyInfo>,
        revoked: Boolean = false,
    ) = GpgPublicKeyInfo(
        fingerprint = "PRIMARY",
        keyId = "PRIMARY",
        algorithm = "Ed25519",
        bitStrength = 255,
        userIds = emptyList(),
        emails = emptyList(),
        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        expiresAt = expiresAt,
        revoked = revoked,
        canSign = true,
        canEncrypt = false,
        publicKeyArmored = "public",
        subKeys = subKeys,
    )

    private fun subKey(
        fingerprint: String,
        createdAt: Instant = Instant.parse("2024-01-01T00:00:00Z"),
        expiresAt: Instant?,
        revoked: Boolean = false,
    ) = GpgPublicSubKeyInfo(
        fingerprint = fingerprint,
        keyId = fingerprint,
        algorithm = "Ed25519",
        canSign = true,
        canEncrypt = false,
        revoked = revoked,
        createdAt = createdAt,
        expiresAt = expiresAt,
    )
}
