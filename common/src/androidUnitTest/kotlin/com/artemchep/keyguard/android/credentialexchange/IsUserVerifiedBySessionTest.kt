package com.artemchep.keyguard.android.credentialexchange

import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import org.kodein.di.DI
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The one boolean that decides whether a whole-vault export may skip its
 * user-presence gate. Both halves of the condition have to hold, and neither is
 * observable from the export screen itself — hence a test at this seam.
 */
class IsUserVerifiedBySessionTest {
    private val startedAt = Instant.fromEpochSeconds(1_000_000)

    private fun session(
        createdAt: Instant,
        origin: MasterSession.Key.Origin,
    ) = MasterSession.Key(
        masterKey = MasterKey(
            version = MasterKdfVersion.V1,
            byteArray = byteArrayOf(1, 2, 3),
        ),
        di = DI {},
        origin = origin,
        createdAt = createdAt,
    )

    @Test
    fun `a password typed after the request arrived counts as presence`() {
        val verified = isUserVerifiedBySession(
            session = session(
                createdAt = startedAt + 5.seconds,
                origin = MasterSession.Key.Authenticated,
            ),
            startedAt = startedAt,
        )
        assertTrue(verified)
    }

    @Test
    fun `a session restored from disk proves nothing, however fresh`() {
        // The vault may well have unlocked itself while this request was on screen;
        // that says the key was on disk, not that anyone is holding the phone.
        val verified = isUserVerifiedBySession(
            session = session(
                createdAt = startedAt + 5.seconds,
                origin = MasterSession.Key.Persisted,
            ),
            startedAt = startedAt,
        )
        assertFalse(verified)
    }

    @Test
    fun `a password typed before the request arrived does not count`() {
        // An already-unlocked app is the ordinary case: the user authenticated for
        // something else entirely, possibly hours ago, and never saw this transfer.
        val verified = isUserVerifiedBySession(
            session = session(
                createdAt = startedAt - 1.seconds,
                origin = MasterSession.Key.Authenticated,
            ),
            startedAt = startedAt,
        )
        assertFalse(verified)
    }

    @Test
    fun `a session created at the very instant the request arrived does not count`() {
        // Strictly after, not at-or-after: an equal timestamp cannot distinguish a
        // session this request caused from one that was already there, and the gate
        // is what we keep when we cannot tell.
        val verified = isUserVerifiedBySession(
            session = session(
                createdAt = startedAt,
                origin = MasterSession.Key.Authenticated,
            ),
            startedAt = startedAt,
        )
        assertFalse(verified)
    }
}
