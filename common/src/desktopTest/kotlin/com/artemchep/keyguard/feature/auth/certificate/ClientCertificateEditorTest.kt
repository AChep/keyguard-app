package com.artemchep.keyguard.feature.auth.certificate

import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.ConfirmationState
import com.artemchep.keyguard.feature.confirmation.confirmationStateProducer
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactoryDefault
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.nativecrypto.NativeCryptoPkcs12
import com.artemchep.keyguard.platform.leParseUri
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestMarkBackupAsDirty
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import com.artemchep.keyguard.provider.bitwarden.sync.v2.insertUploadTestAccount
import com.artemchep.keyguard.provider.bitwarden.transport.MutualTlsCertificateMetadata
import com.artemchep.keyguard.provider.bitwarden.transport.MutualTlsImportResult
import com.artemchep.keyguard.provider.bitwarden.transport.MutualTlsService
import com.artemchep.keyguard.provider.bitwarden.usecase.PutAccountMutualTls
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ClientCertificateEditorTest {
    @Test
    fun `wrong password retries without rereading and clears file bytes after import`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.passwords.addAll(listOf("wrong", "correct"))
            fixture.service.result = { password ->
                when (password) {
                    null -> MutualTlsImportResult.PasswordRequired
                    "correct" -> MutualTlsImportResult.Success(fixture.imported, metadata(""))
                    else -> MutualTlsImportResult.InvalidPassword
                }
            }
            assertEquals(fixture.imported, fixture.import())
            assertEquals(1, fixture.reads)
            assertEquals(listOf(null, "wrong", "correct"), fixture.service.attempts)
            assertTrue(fixture.service.bytes!!.all { it == 0.toByte() })
            assertEquals(listOf(fixture.file.uri.toString()), fixture.cleanedFiles)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun `cancelled password and oversized file leave no imported identity`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.service.result = { MutualTlsImportResult.PasswordRequired }
            assertNull(fixture.import())
            assertTrue(fixture.service.bytes!!.all { it == 0.toByte() })
            fixture.fileSize = NativeCryptoPkcs12.MAX_BUNDLE_BYTES + 1
            fixture.service.attempts.clear()
            assertNull(fixture.import())
            assertTrue(fixture.service.attempts.isEmpty())
            assertEquals(List(2) { fixture.file.uri.toString() }, fixture.cleanedFiles)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun `failed certificate parsing cleans the managed file and clears bytes`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.service.result = { error("Invalid certificate") }
            assertNull(fixture.import())
            assertEquals(listOf(fixture.file.uri.toString()), fixture.cleanedFiles)
            assertTrue(fixture.service.bytes!!.all { it == 0.toByte() })
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun `known oversized file is rejected before opening it`() = runBlocking {
        val fixture = Fixture()
        try {
            val oversized = fixture.file.copy(size = NativeCryptoPkcs12.MAX_BUNDLE_BYTES.toLong() + 1)
            assertNull(fixture.import(oversized))
            assertEquals(0, fixture.reads)
            assertTrue(fixture.service.attempts.isEmpty())
            assertEquals(listOf(oversized.uri.toString()), fixture.cleanedFiles)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun `file at the size limit is imported and cleared`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.fileSize = NativeCryptoPkcs12.MAX_BUNDLE_BYTES
            assertEquals(fixture.imported, fixture.import())
            assertEquals(1, fixture.reads)
            assertEquals(fixture.fileSize, fixture.service.bytes!!.size)
            assertTrue(fixture.service.bytes!!.all { it == 0.toByte() })
            assertEquals(listOf(fixture.file.uri.toString()), fixture.cleanedFiles)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun `cancelling import during password entry still cleans the managed file`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.holdPasswordPrompt = true
            fixture.service.result = { MutualTlsImportResult.PasswordRequired }
            val importing = fixture.scope.async { fixture.import() }
            withTimeout(5_000) { fixture.passwordPrompt.await() }
            importing.cancelAndJoin()
            assertEquals(listOf(fixture.file.uri.toString()), fixture.cleanedFiles)
            assertTrue(fixture.service.bytes!!.all { it == 0.toByte() })
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun `removal asks once and replacement waits for save even when expired`() = runBlocking {
        val fixture = Fixture()
        try {
            val states = fixture.states()
            val original = states.await { it.certificate?.displayName == "original" }
            assertNull(original.onSave)
            assertFalse(original.unsaved)
            original.onRemove!!.invoke()
            val confirmation = states.await { it.confirmRemoval }
            confirmation.onDismissRemoval()
            assertEquals(fixture.original, fixture.current().env.mutualTls)
            val editable = states.await { !it.confirmRemoval }
            fixture.pick(editable)
            val replacement = states.await { it.certificate?.displayName == "replacement" && !it.busy }
            // Expired certificates warn but can be saved.
            assertEquals(ClientCertificateStatus.EXPIRED, replacement.certificate?.status)
            assertTrue(replacement.unsaved)
            assertNotNull(replacement.onSave)
            assertEquals(fixture.original, fixture.current().env.mutualTls)
            fixture.scope.cancel()
            replacement.onSave!!.invoke() // Late UI callbacks after lock/close are inert.
            assertEquals(fixture.original, fixture.current().env.mutualTls)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun `confirmed removal applies at once`() = runBlocking {
        val fixture = Fixture()
        try {
            val states = fixture.states()
            states.await { it.certificate?.displayName == "original" }.onRemove!!.invoke()
            states.await { it.confirmRemoval }.onConfirmRemoval()
            withTimeout(5_000) {
                while (fixture.current().env.mutualTls != null) kotlinx.coroutines.delay(10)
            }
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun `dropped file is staged and an unsaved draft is discarded without asking`() = runBlocking {
        val fixture = Fixture(saved = false)
        try {
            val states = fixture.states()
            val empty = states.await { it.certificate == null && it.onFileDrop != null }
            assertNull(empty.onRemove)
            empty.onFileDrop!!.invoke(fixture.file)
            val dropped = states.await { it.certificate?.displayName == "replacement" && !it.busy }
            assertTrue(dropped.unsaved)
            dropped.onRemove!!.invoke()
            val discarded = states.await { it.certificate == null }
            assertFalse(discarded.confirmRemoval)
            assertFalse(discarded.unsaved)
            assertNull(fixture.current().env.mutualTls)
        } finally { fixture.scope.cancel() }
    }

    @Test
    fun `certificate password dialog never registers restorable state`() = runBlocking {
        val fixture = Fixture()
        var result: ConfirmationResult? = null
        try {
            val states = fixture.screen.confirmationStateProducer(
                args = ConfirmationRoute.Args(items = listOf(ConfirmationRoute.Args.Item.StringItem(
                    key = "password", title = "Password",
                    type = ConfirmationRoute.Args.Item.StringItem.Type.Password, ephemeral = true,
                ))),
                transmitter = object : RouteResultTransmitter<ConfirmationResult> {
                    override fun invoke(unit: ConfirmationResult) { result = unit }
                },
            ).stateIn(fixture.scope, SharingStarted.Eagerly, null)
            fun ConfirmationState.field() = items.getOrNull()?.single() as? ConfirmationState.Item.StringItem
            val initial = withTimeout(5_000) { states.filterNotNull().first { it.field() != null } }
            assertTrue(initial.field()!!.ephemeral)
            assertNull(initial.field()!!.generator)
            initial.field()!!.state.onChange?.invoke("secret")
            val edited = withTimeout(5_000) { states.filterNotNull().first { it.field()?.value == "secret" } }
            edited.onConfirm!!.invoke()
            assertEquals("secret", (result as ConfirmationResult.Confirm).data["password"])
            // The fixture rejects every persistence API, so reaching this point proves none was called.
        } finally { fixture.scope.cancel() }
    }

    private class Fixture(saved: Boolean = true) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val original = ServerEnv.MutualTls.ImportedPkcs12("original").takeIf { saved }
        val imported = ServerEnv.MutualTls.ImportedPkcs12("replacement")
        val database = createUploadTestDatabase().apply { insertUploadTestAccount() }
        val manager = UploadTestVaultDatabaseManager(database)
        val initial = (database.accountQueries.get().executeAsOne().data_ as BitwardenToken).copy(
            env = BitwardenToken.Environment(baseUrl = "https://vault.example.com", mutualTls = original),
        ).also { database.accountQueries.insert(accountId = it.id, data = it) }
        val passwords = ArrayDeque<String>()
        val passwordPrompt = CompletableDeferred<Unit>()
        var holdPasswordPrompt = false
        var reads = 0
        var fileSize = 10
        val cleanedFiles = mutableListOf<String>()
        val file = FilePickerResult(leParseUri("file:///certificate.p12"), "certificate.p12", null)
        val files = object : FileService {
            override fun exists(uri: String): Boolean = error("Unexpected file operation")
            override fun writeToFile(uri: String): Sink = error("Unexpected file operation")
            override fun delete(uri: String): Boolean = error("Unexpected file operation")

            override fun readFromFile(uri: String): Source {
                reads++
                return Buffer().apply { write(ByteArray(fileSize) { 1 }) }
            }

            override fun deleteManagedSourceFile(uri: String): Boolean {
                cleanedFiles += uri
                return true
            }
        }
        val service = FakeCertificates(imported)
        val save = PutAccountMutualTls(
            manager, UploadTestMarkBackupAsDirty,
            object : QueueSyncById { override fun invoke(accountId: AccountId) = io(Unit) },
            object : WindowCoroutineScope { override val coroutineContext = scope.coroutineContext },
        )
        val screen: RememberStateFlowScope = fake { method, args ->
            when (method) {
                "getCoroutineContext" -> scope.coroutineContext
                "getAppScope", "getScreenScope" -> scope
                "getScreenId" -> "certificate"
                "copier" -> CopyText(fake<ClipboardService> { _, _ -> Unit }, screen, onMessage = {})
                "translate" -> "Test"
                "message" -> Unit
                "navigate" -> {
                    val intent = args[0]
                    if (intent is NavigationIntent.NavigateToRoute) {
                        @Suppress("UNCHECKED_CAST")
                        val route = intent.route as RouteResultReceiver<ConfirmationResult>
                        val field = (route.innerRoute as ConfirmationRoute).args.items.single()
                            as ConfirmationRoute.Args.Item.StringItem
                        assertTrue(field.ephemeral)
                        passwordPrompt.complete(Unit)
                        if (!holdPasswordPrompt) {
                            val password = passwords.removeFirstOrNull()
                            route.resultTransmitter(if (password == null) ConfirmationResult.Deny else {
                                ConfirmationResult.Confirm(mapOf(field.key to password))
                            })
                        }
                    }
                    Unit
                }
                else -> error("Unexpected screen method: $method")
            }
        }
        fun current() = database.accountQueries.getByAccountId(initial.id).executeAsOne().data_ as BitwardenToken
        suspend fun import(info: FilePickerResult = file) = screen.importClientCertificate(
            info, files, service, ConfirmationRouteFactoryDefault,
        )?.imported

        suspend fun states() = screen.accountClientCertificateStateProducer(
            initial.id, manager, save, files, service,
            ConfirmationRouteFactoryDefault, fake<DateFormatter> { _, _ -> "2020-01-01" },
        ).map { it.getOrNull() }.stateIn(scope, SharingStarted.Eagerly, null)

        suspend fun pick(state: AccountClientCertificateState) {
            val picker = scope.async { state.filePickerIntents.first() }
            // Wait for the picker collector before publishing the one-shot intent.
            withTimeout(5_000) {
                val events = state.filePickerIntents as com.artemchep.keyguard.common.util.flow.EventFlow<*>
                while (!events.isActive) kotlinx.coroutines.yield()
            }
            state.onImport!!.invoke()
            (picker.await() as FilePickerIntent.OpenDocument).onResult(file)
        }
    }

    private suspend fun StateFlow<AccountClientCertificateState?>.await(
        predicate: (AccountClientCertificateState) -> Boolean,
    ) = withTimeout(5_000) { filterNotNull().first(predicate) }

    private class FakeCertificates(imported: ServerEnv.MutualTls.ImportedPkcs12) : MutualTlsService {
        val attempts = mutableListOf<String?>()
        var bytes: ByteArray? = null
        var result: (String?) -> MutualTlsImportResult = {
            MutualTlsImportResult.Success(imported, metadata(imported.pkcs12Base64))
        }
        override fun importPkcs12(sourcePkcs12: ByteArray, password: String?): MutualTlsImportResult {
            bytes = sourcePkcs12
            attempts += password
            return result(password)
        }
        override fun describeImportedPkcs12(pkcs12Base64: String) = metadata(pkcs12Base64)
    }
}

private fun metadata(name: String) = MutualTlsCertificateMetadata(
    displayName = name,
    subject = "CN=$name",
    issuer = "CN=Issuer",
    issuerName = "Issuer",
    validFrom = Instant.fromEpochSeconds(-86_400),
    expiresAt = Instant.fromEpochSeconds(0),
    serialNumber = "01",
    sha256Fingerprint = "AB:CD",
    keyType = "RSA 2048",
)

private inline fun <reified T> fake(crossinline call: (String, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        call(method.name, args.orEmpty())
    } as T
