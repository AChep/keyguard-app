package com.artemchep.keyguard.feature.attachments

import arrow.core.right
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AttachmentPreviewPolicy
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.RemoveAttachmentRequest
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.model.testCipherFilterContext
import com.artemchep.keyguard.common.service.download.DownloadInfoEntity
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewRouteFactoryDefault
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewRouteFactoryDefault
import com.artemchep.keyguard.feature.home.vault.search.TEST_INSTANT
import com.artemchep.keyguard.feature.home.vault.search.createSecret
import com.artemchep.keyguard.feature.home.vault.search.filter.FilterHolder
import com.artemchep.keyguard.feature.navigation.state.DiskHandle
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.Selection
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Select all on the Attachments screen must only pick the attachments
 * that pass the active filter, otherwise the bulk actions act on
 * attachments the user can not see.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentsSelectAllFilterTest {
    private val cipherA = createSecret(id = "cipher-a")
        .copy(
            accountId = "account-a",
            attachments = listOf(
                attachment(id = "a1", cipherId = "cipher-a"),
                attachment(id = "a2", cipherId = "cipher-a"),
            ),
        )
    private val cipherB = createSecret(id = "cipher-b")
        .copy(
            accountId = "account-b",
            attachments = listOf(
                attachment(id = "b1", cipherId = "cipher-b"),
            ),
        )
    private val ciphers = listOf(cipherA, cipherB)
    private val downloads = ciphers
        .flatMap { cipher ->
            cipher.attachments
                .map { attachment -> download(cipher, attachment) }
        }

    @Test
    fun `select all picks only the visible attachments`() = runTest {
        val removed = mutableListOf<RemoveAttachmentRequest>()
        val screen = testScreenScope(backgroundScope)
        screen.filterByAccount(cipherA.accountId)
        val states = screen.attachmentsState(removed)

        val visible = states.value.attachmentIds()
        assertEquals(setOf("a1", "a2"), visible)

        // Select one of the visible items by a long click,
        // as a user would do.
        states.value.attachmentItems()
            .first { it.name == "a1" }
            .selectableState.value
            .onLongClick!!
            .invoke()
        assertEquals(1, assertNotNull(states.first { it.selection != null }.selection).count)
        assertNotNull(states.value.selection?.onSelectAll).invoke()

        val selection = assertNotNull(states.first { it.selection?.count != 1 }.selection)
        assertEquals(2, selection.count)
        assertNull(selection.onSelectAll)
        assertEquals(
            setOf(true),
            states.value.attachmentItems().map { it.selectableState.value.selected }.toSet(),
        )
        selection.action("attachments.selection.deleteLocal").invoke()
        runCurrent()
        assertEquals(
            setOf("a1", "a2"),
            removed.map { (it as RemoveAttachmentRequest.ByLocalCipherAttachment).attachmentId }.toSet(),
        )
    }

    @Test
    fun `select all keeps an item that was selected before it got filtered out`() = runTest {
        val screen = testScreenScope(backgroundScope)
        val states = screen.attachmentsState(mutableListOf())
        assertEquals(setOf("a1", "a2", "b1"), states.value.attachmentIds())

        states.value.attachmentItems()
            .first { it.name == "b1" }
            .selectableState.value
            .onLongClick!!
            .invoke()
        assertEquals(1, states.first { it.selection != null }.selection?.count)

        screen.filterByAccount(cipherA.accountId)
        assertEquals(setOf("a1", "a2"), states.first { it.attachmentIds().size == 2 }.attachmentIds())
        // The explicit selection survives the filter change,
        // as it does on the vault list.
        assertEquals(1, assertNotNull(states.value.selection).count)
        assertNotNull(states.value.selection?.onSelectAll).invoke()
        val selection = assertNotNull(states.first { it.selection?.count != 1 }.selection)
        assertEquals(3, selection.count)
        assertNull(selection.onSelectAll)
    }

    private fun AttachmentsState.attachmentItems() = items
        .filterIsInstance<AttachmentsState.Item.Attachment>()
        .map { it.item }

    private fun AttachmentsState.attachmentIds() = attachmentItems()
        .map { it.name }
        .toSet()

    private fun Selection.action(id: String) = actions
        .filterIsInstance<FlatItemAction>()
        .first { it.id == id }
        .onClick!!

    // Runs the real producer with every collaborator either constant or unused.
    private suspend fun TestScreenScope.attachmentsState(
        removed: MutableList<RemoveAttachmentRequest>,
    ) = scope.attachmentsScreenStateProducer(
        filterContext = testCipherFilterContext(),
        addCipherFilter = unused(),
        confirmationRouteFactory = unused(),
        getCipherFilters = constant(flowOf(emptyList<Any>())),
        getAccounts = constant(flowOf(emptyList<Any>())),
        getProfiles = constant(flowOf(emptyList<Any>())),
        getCiphers = constant(flowOf(ciphers)),
        getFolders = constant(flowOf(emptyList<Any>())),
        getTags = constant(flowOf(emptyList<Any>())),
        getCollections = constant(flowOf(emptyList<Any>())),
        getOrganizations = constant(flowOf(emptyList<Any>())),
        downloadRepository = proxy { method, _ ->
            check(method == "get") { "Unexpected DownloadRepository.$method" }
            flowOf(downloads)
        },
        downloadManager = proxy { method, _ ->
            check(method == "statusByTag") { "Unexpected DownloadManager.$method" }
            flowOf(DownloadProgress.Complete("file:///downloaded".right()))
        },
        // The per-item IOs are built up-front, so they must exist.
        downloadAttachment = constant(noopIo),
        removeAttachment = proxy { method, args ->
            check(method == "invoke") { "Unexpected RemoveAttachment.$method" }
            @Suppress("UNCHECKED_CAST")
            val requests = args[0] as List<RemoveAttachmentRequest>
            // Record on execution, the producer builds
            // an IO per item eagerly.
            val io: IO<Unit> = { removed += requests }
            io
        },
        canPreviewAttachment = constant(AttachmentPreviewPolicy.UnsupportedType),
        attachmentPreviewRouteFactory = AttachmentPreviewRouteFactoryDefault,
        vaultViewRouteFactory = VaultViewRouteFactoryDefault,
    )
        .stateIn(coroutineScope, SharingStarted.Eagerly, null)
        .let { loadable ->
            loadable.first { it != null }
            States(loadable)
        }

    private fun TestScreenScope.filterByAccount(accountId: String) {
        val filter = FilterHolder(
            state = mapOf(
                "account" to setOf(DFilter.ById(accountId, DFilter.ById.What.ACCOUNT)),
            ),
        )
        persisted.getOrPut("ciphers.filters") { MutableStateFlow(filter) }.value = filter
    }
}

private val noopIo: IO<Unit> = {}

private class States(
    private val loadable: StateFlow<Loadable<AttachmentsState>?>,
) {
    val value: AttachmentsState
        get() = loadable.value!!.getOrNull()!!

    suspend fun first(predicate: (AttachmentsState) -> Boolean): AttachmentsState =
        loadable.first { it != null && predicate(it.getOrNull()!!) }!!.getOrNull()!!
}

private class TestScreenScope(
    val coroutineScope: CoroutineScope,
    val persisted: MutableMap<String, MutableStateFlow<Any?>>,
    val scope: RememberStateFlowScope,
)

// Only the screen lifecycle, persistence and translations are faked. The test
// executes the real producer, filter and selection flows.
private fun testScreenScope(
    scope: CoroutineScope,
): TestScreenScope {
    val disk = object : DiskHandle {
        override val restoredState = emptyMap<String, Any?>()
        override fun link(key: String, flow: Flow<Any?>) = Unit
        override fun unlink(key: String) = Unit
    }
    val persisted = mutableMapOf<String, MutableStateFlow<Any?>>()
    val screen = proxy<RememberStateFlowScope> { method, args ->
        when (method) {
            "getCoroutineContext" -> scope.coroutineContext
            "getAppScope", "getScreenScope" -> scope
            "isStartedFlow" -> flowOf(false)
            "loadDiskHandle" -> disk
            "translate" -> "Test"
            "interceptBackPress" -> { -> }
            "action" -> {
                @Suppress("UNCHECKED_CAST")
                val action = args[0] as suspend () -> Unit
                scope.launch { action() }
                Unit
            }
            "mutablePersistedFlow" -> persisted.getOrPut(args[0] as String) {
                @Suppress("UNCHECKED_CAST")
                val initial = args.last() as () -> Any?
                MutableStateFlow(initial())
            }
            else -> error("Unexpected screen call: $method")
        }
    }
    return TestScreenScope(scope, persisted, screen)
}

private fun attachment(
    id: String,
    cipherId: String,
) = DSecret.Attachment.Remote(
    id = id,
    url = "https://vault.example.com/$id",
    remoteCipherId = cipherId,
    fileName = id,
    keyBase64 = null,
    size = 16L,
)

private fun download(
    cipher: DSecret,
    attachment: DSecret.Attachment,
) = DownloadInfoEntity(
    id = "download-${attachment.id}",
    localCipherId = cipher.id,
    remoteCipherId = cipher.service.remote?.id,
    attachmentId = attachment.id,
    url = attachment.url.orEmpty(),
    urlIsOneTime = false,
    name = attachment.id,
    createdDate = TEST_INSTANT,
)

private inline fun <reified T> constant(value: Any): T = proxy { method, _ ->
    check(method == "invoke") { "Unexpected ${T::class.simpleName}.$method" }
    value
}

private inline fun <reified T> unused(): T = proxy { method, _ ->
    error("Unexpected ${T::class.simpleName}.$method")
}

// Interface default methods, such as the sharing helpers of the
// screen scope, run as written; everything else goes to the handler.
private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
        if (method.isDefault) {
            InvocationHandler.invokeDefault(proxy, method, *args.orEmpty())
        } else {
            call(method.name, args.orEmpty())
        }
    } as T
