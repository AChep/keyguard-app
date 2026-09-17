package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.extract.LinkInfoExtractorRegistry
import com.artemchep.keyguard.common.service.keyboard.KeyboardShortcutsService
import com.artemchep.keyguard.common.service.keyboard.KeyboardShortcutsServiceHost
import com.artemchep.keyguard.common.service.keyboard.KeyboardShortcutsServiceImpl
import com.artemchep.keyguard.common.service.logging.LogSinkRegistry
import com.artemchep.keyguard.common.service.placeholder.PlaceholderFactoryRegistry
import com.artemchep.keyguard.common.service.placeholder.impl.CipherPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.CommentPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.CustomPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.DateTimePlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.EnvironmentPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.TextReplaceRegexPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.TextTransformPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.UrlPlaceholder
import com.artemchep.keyguard.common.service.relays.EmailRelayRegistry
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.common.service.totp.TotpService
import com.artemchep.keyguard.common.service.vault.SessionReadRepository
import com.artemchep.keyguard.common.service.vault.SessionReadWriteRepository
import com.artemchep.keyguard.common.service.vault.impl.SessionRepositoryImpl
import com.artemchep.keyguard.common.usecase.MessageHub
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.usecase.impl.MessageHubImpl
import com.artemchep.keyguard.common.worker.WorkerRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.koin.core.Koin
import org.koin.dsl.module

/** Runtime contracts for selected services; application roots validate the complete platform graphs. */
class ApplicationModuleContractsTest {
    @Test
    fun `vault window work is isolated from the application and other unlocks`() {
        val rootJob = SupervisorJob()
        val rootWindow = object : WindowCoroutineScope {
            override val coroutineContext = rootJob
        }
        val koin = Koin()
        koin.loadModules(
            listOf(
                VaultOperationsModule().module,
                module {
                    single<WindowCoroutineScope> { rootWindow }
                    single<ShowMessage> { MessageHubImpl() }
                },
            ),
        )
        try {
            val factory = KoinVaultSessionFactory(koin)
            val masterKey = MasterKey(MasterKdfVersion.LATEST, byteArrayOf(1))
            val first = factory.create(masterKey)
            val second = factory.create(masterKey)
            val firstWindow = checkNotNull(first.resolve { get<WindowCoroutineScope>() })
            val secondWindow = checkNotNull(second.resolve { get<WindowCoroutineScope>() })

            assertNotSame(rootWindow, firstWindow)
            assertNotSame(firstWindow, secondWindow)
            first.close()
            assertFalse(firstWindow.coroutineContext[Job]!!.isActive)
            assertTrue(secondWindow.coroutineContext[Job]!!.isActive)
            assertTrue(rootJob.isActive)
            assertSame(rootWindow, koin.get<WindowCoroutineScope>())
        } finally {
            koin.close()
            rootJob.cancel()
        }
    }

    @Test
    fun `repository and UI interfaces share their implementation instance`() {
        val koin = Koin()
        koin.loadModules(
            listOf(
                ApplicationAuthenticationModule().module,
                ApplicationUiModule().module,
                module { single<CryptoGenerator> { UnusedCryptoGenerator } },
            ),
        )
        try {
            val sessionRepository = koin.get<SessionRepositoryImpl>()
            assertSame(sessionRepository, koin.get<SessionReadRepository>())
            assertSame(sessionRepository, koin.get<SessionReadWriteRepository>())
            assertSame(sessionRepository, koin.get<SessionReadRepository>())

            val messageHub = koin.get<MessageHubImpl>()
            assertSame(messageHub, koin.get<MessageHub>())
            assertSame(messageHub, koin.get<ShowMessage>())

            val keyboardShortcuts = koin.get<KeyboardShortcutsServiceImpl>()
            assertSame(keyboardShortcuts, koin.get<KeyboardShortcutsService>())
            assertSame(keyboardShortcuts, koin.get<KeyboardShortcutsServiceHost>())
        } finally {
            koin.close()
        }
    }

    @Test
    fun `typed registries coexist and preserve production matching and display order`() {
        val httpClient = HttpClient(MockEngine { error("Resolving relay providers must not make requests") })
        val logSinks = LogSinkRegistry(emptyList())
        val extractors = LinkInfoExtractorRegistry(emptyList())
        val workers = WorkerRegistry(emptyList())
        val koin = Koin()
        koin.loadModules(
            listOf(
                ApplicationGeneratorsModule().module,
                module {
                    single { httpClient }
                    single<Base64Service> { Base64ServiceImpl() }
                    single<CryptoGenerator> { UnusedCryptoGenerator }
                    single<TotpService> {
                        object : TotpService {
                            override fun generate(token: TotpToken, timestamp: Instant, offset: Int): Nothing =
                                error("Resolving placeholder factories must not generate codes")
                        }
                    }
                    single { logSinks }
                    single { extractors }
                    single { workers }
                },
            ),
        )
        try {
            val placeholders = koin.get<PlaceholderFactoryRegistry>()
            assertEquals(
                listOf(
                    CipherPlaceholder.Factory::class,
                    CommentPlaceholder.Factory::class,
                    CustomPlaceholder.Factory::class,
                    DateTimePlaceholder.Factory::class,
                    EnvironmentPlaceholder.Factory::class,
                    TextReplaceRegexPlaceholder.Factory::class,
                    TextTransformPlaceholder.Factory::class,
                    UrlPlaceholder.Factory::class,
                ),
                placeholders.values.map { it::class },
            )
            assertSame(placeholders, koin.get<PlaceholderFactoryRegistry>())

            val relays = koin.get<EmailRelayRegistry>()
            assertEquals(EXPECTED_RELAY_TYPES, relays.values.map { it.type })
            assertSame(relays, koin.get<EmailRelayRegistry>())
            assertSame(logSinks, koin.get<LogSinkRegistry>())
            assertSame(extractors, koin.get<LinkInfoExtractorRegistry>())
            assertSame(workers, koin.get<WorkerRegistry>())
        } finally {
            koin.close()
            httpClient.close()
        }
    }

    private object UnusedCryptoGenerator : CryptoGenerator {
        override fun hkdf(seed: ByteArray, salt: ByteArray?, info: ByteArray?, length: Int): Nothing = unused()
        override fun pbkdf2(seed: ByteArray, salt: ByteArray, iterations: Int, length: Int): Nothing = unused()
        override fun argon2(
            mode: Argon2Mode,
            seed: ByteArray,
            salt: ByteArray,
            iterations: Int,
            memoryKb: Int,
            parallelism: Int,
        ): Nothing = unused()
        override fun seed(length: Int): Nothing = unused()
        override fun hmac(key: ByteArray, data: ByteArray, algorithm: CryptoHashAlgorithm): Nothing = unused()
        override fun hashSha1(data: ByteArray): Nothing = unused()
        override fun hashSha256(data: ByteArray): Nothing = unused()
        override fun hashMd5(data: ByteArray): Nothing = unused()
        override fun uuid(): Nothing = unused()
        override fun random(): Nothing = unused()
        override fun random(range: IntRange): Nothing = unused()
        private fun unused(): Nothing = error("Resolving services must not perform cryptographic operations")
    }

    private companion object {
        val EXPECTED_RELAY_TYPES = listOf(
            "AnonAddy",
            "CloudflareEmailRouting",
            "DuckDuckGo",
            "Fastmail",
            "FirefoxRelay",
            "ForwardEmail",
            "SimpleLogin",
        )
    }
}
