package com.artemchep.keyguard.common.service.sshagent.bookmarks.impl

import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.SshAgentFilter
import com.artemchep.keyguard.common.service.sshagent.TEST_ED25519_HOST_CERTIFICATE
import com.artemchep.keyguard.common.service.sshagent.TEST_ED25519_PUBLIC_KEY
import com.artemchep.keyguard.common.service.sshagent.TEST_ED25519_USER_CERTIFICATE
import com.artemchep.keyguard.common.service.sshagent.TestSshCertificateParser
import com.artemchep.keyguard.common.service.sshagent.buildOpenSshPublicKey
import com.artemchep.keyguard.common.service.sshagent.buildOpenSshPublicKeyBlob
import com.artemchep.keyguard.common.service.sshagent.testGetSshAgentCiphers
import com.artemchep.keyguard.common.service.sshagent.bookmarks.SshBookmark
import com.artemchep.keyguard.common.service.sshagent.bookmarks.SshBookmarks
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.copy.Base64ServiceJvm
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.test.createSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SshBookmarksSourceImplTest {
    private val cryptoGenerator = CryptoGeneratorJvm()
    private val base64Service = Base64ServiceJvm()

    @Test
    fun `pins the bookmarks of the keys that the agent offers`() = runTest {
        val source = createSource(
            ciphers = MutableStateFlow(mixedSecrets()),
            filter = MutableStateFlow(
                SshAgentFilter(
                    state = mapOf(
                        "not-filtered" to setOf(
                            DFilter.ById(id = "served", what = DFilter.ById.What.CIPHER),
                            DFilter.ById(id = "unsigned", what = DFilter.ById.What.CIPHER),
                        ),
                    ),
                ),
            ),
        )

        val bookmarks = source.get().first()

        assertEquals(
            listOf(SshBookmark(host = "github.com", user = "git", port = null)),
            bookmarks.entries.map { it.bookmark },
        )
        assertEquals(blobSha256(seed = 1), bookmarks.entries.single().identity.keySha256)
        // The pattern, the filtered key and the key without a private key.
        assertEquals(3, bookmarks.skipped)
    }

    @Test
    fun `follows the items`() = runTest {
        val ciphers = MutableStateFlow(
            listOf(sshSecret(id = "key", seed = 1, bookmarks = listOf("ssh://one.example.com"))),
        )
        val values = mutableListOf<SshBookmarks>()
        val job = launch {
            createSource(ciphers = ciphers).get().collect { values += it }
        }
        try {
            runCurrent()
            assertEquals(listOf("one.example.com"), values.last().hosts())

            ciphers.value = listOf(sshSecret(id = "key", seed = 1, bookmarks = listOf("ssh://two.example.com")))
            runCurrent()
            assertEquals(listOf("two.example.com"), values.last().hosts())
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `adds the user certificate of the key`() = runTest {
        val certified = createSecret(
            id = "certified",
            type = DSecret.Type.SshKey,
            sshKey = DSecret.SshKey(
                privateKey = "private-key-placeholder",
                publicKey = TEST_ED25519_PUBLIC_KEY,
                fingerprint = "SHA256:certified",
                certificate = "\n$TEST_ED25519_USER_CERTIFICATE\n",
                bookmarks = bookmarks("ssh://ca.example.com"),
            ),
        )
        val host = certified.copy(
            id = "host",
            sshKey = certified.sshKey?.copy(
                certificate = TEST_ED25519_HOST_CERTIFICATE,
                bookmarks = bookmarks("ssh://host.example.com"),
            ),
        )

        val bookmarks = createSource(ciphers = MutableStateFlow(listOf(certified, host)))
            .get()
            .first()

        assertEquals(
            mapOf(
                "ca.example.com" to TEST_ED25519_USER_CERTIFICATE.substringBeforeLast(' '),
                "host.example.com" to null,
            ),
            bookmarks.entries.associate { it.bookmark.host to it.identity.certificate?.certificate },
        )
    }

    private fun TestScope.createSource(
        ciphers: Flow<List<DSecret>>,
        filter: Flow<SshAgentFilter> = MutableStateFlow(SshAgentFilter()),
    ): SshBookmarksSourceImpl {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return SshBookmarksSourceImpl(
            getCiphers = object : GetCiphers {
                override fun invoke(): Flow<List<DSecret>> = ciphers
            },
            getSshAgentCiphers = testGetSshAgentCiphers(
                ciphers = ciphers,
                filter = filter,
                dispatcher = dispatcher,
            ),
            sshCertificateParser = TestSshCertificateParser,
            cryptoGenerator = cryptoGenerator,
            base64Service = base64Service,
            defaultDispatcher = dispatcher,
        )
    }

    private fun SshBookmarks.hosts() = entries.map { it.bookmark.host }

    /**
     * A served key with a valid and a pattern bookmark, plus bookmarks of a
     * filtered key, a key without a private key and a trashed key, and a
     * login whose text field the sync left alone.
     */
    private fun mixedSecrets() = listOf(
        sshSecret(
            id = "served",
            seed = 1,
            bookmarks = listOf("ssh://git@github.com", "ssh://*.example.com"),
        ),
        sshSecret(
            id = "filtered",
            seed = 2,
            bookmarks = listOf("ssh://filtered.example.com"),
        ),
        sshSecret(
            id = "unsigned",
            seed = 3,
            privateKey = null,
            bookmarks = listOf("ssh://unsigned.example.com"),
        ),
        sshSecret(
            id = "trashed",
            seed = 4,
            deletedDate = Instant.parse("2024-02-01T00:00:00Z"),
            bookmarks = listOf("ssh://trashed.example.com"),
        ),
        createSecret(
            id = "login",
            type = DSecret.Type.Login,
            fields = listOf(
                DSecret.Field(name = "SSH", value = "ssh://login.example.com", type = DSecret.Field.Type.Text),
            ),
        ),
    )

    private fun bookmarks(vararg urls: String) = urls.map { url ->
        DSecret.SshKey.Bookmark(
            name = "SSH",
            url = url,
        )
    }

    private fun sshSecret(
        id: String,
        seed: Int,
        bookmarks: List<String>,
        privateKey: String? = "private-key-placeholder",
        deletedDate: Instant? = null,
    ): DSecret = createSecret(
        id = id,
        type = DSecret.Type.SshKey,
        deletedDate = deletedDate,
        sshKey = DSecret.SshKey(
            privateKey = privateKey,
            publicKey = buildOpenSshPublicKey("ssh-ed25519", seed),
            fingerprint = "SHA256:$id",
            bookmarks = bookmarks(*bookmarks.toTypedArray()),
        ),
    )

    private fun blobSha256(seed: Int): String = cryptoGenerator
        .hashSha256(buildOpenSshPublicKeyBlob("ssh-ed25519", seed))
        .toHex()
}
