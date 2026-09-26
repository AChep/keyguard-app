package com.artemchep.keyguard.feature.home.vault.folders

import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.model.testCipherFilterContext
import com.artemchep.keyguard.common.service.credentialexchange.cxfFolder
import com.artemchep.keyguard.feature.home.vault.VaultRouteFactoryDefault
import com.artemchep.keyguard.feature.home.vault.search.createSecret
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.ui.icons.generateAccentColors
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The Folders screen must apply the same hidden-account gate as the
 * Watchtower counts that open it, so the empty-folders drilldown lists
 * exactly the folders that were counted.
 */
class FoldersPrivacyGateTest {
    private val visibleEmpty = cxfFolder(id = "visible-empty", name = "Visible", accountId = "visible-account")
    private val visibleUsed = cxfFolder(id = "visible-used", name = "Used", accountId = "visible-account")
    private val hiddenEmpty = cxfFolder(id = "hidden-empty", name = "Hidden", accountId = "hidden-account")
    private val folders = listOf(visibleEmpty, visibleUsed, hiddenEmpty)
    private val ciphers = listOf(
        createSecret(id = "cipher", accountId = "visible-account", folderId = visibleUsed.id),
    )
    private val profiles = listOf(
        profile(accountId = "visible-account", hidden = false),
        profile(accountId = "hidden-account", hidden = true),
    )

    @Test
    fun `global empty-folders drilldown hides hidden accounts' folders`() = runTest {
        val screen = testScreenScope(backgroundScope)
        val items = screen.folderItems(
            // Same shape as the Watchtower empty-folders card
            // without an explicit account filter.
            args = FoldersRoute.Args(
                filter = DFilter.And(filters = listOf(DFilter.All)),
                empty = true,
            ),
            profiles = flowOf(profiles),
        )

        assertEquals(listOf(visibleEmpty.name), items.map { it.title })
    }

    @Test
    fun `explicit account route keeps hidden account's empty folders`() = runTest {
        val screen = testScreenScope(backgroundScope)
        val neverEmittingProfiles = MutableSharedFlow<List<DProfile>>()
        val items = screen.folderItems(
            args = FoldersRoute.Args(
                filter = DFilter.And(
                    filters = listOf(
                        DFilter.All,
                        DFilter.ById(hiddenEmpty.accountId, DFilter.ById.What.ACCOUNT),
                    ),
                ),
                empty = true,
            ),
            profiles = neverEmittingProfiles,
        )

        assertEquals(listOf(hiddenEmpty.name), items.map { it.title })
        assertEquals(0, neverEmittingProfiles.subscriptionCount.value)
    }

    // Runs the real producer with every collaborator either constant or unused.
    private suspend fun RememberStateFlowScope.folderItems(
        args: FoldersRoute.Args,
        profiles: Flow<List<DProfile>>,
    ): List<FoldersState.Content.Item.Folder> {
        val state = foldersScreenStateProducer(
            args = args,
            filterContext = testCipherFilterContext(),
            confirmationRouteFactory = unused(),
            getFolders = constant(flowOf(folders)),
            getCiphers = constant(flowOf(ciphers)),
            getProfiles = constant(profiles),
            getCanWrite = constant(flowOf(true)),
            addFolder = unused(),
            resolveFolderHierarchyMode = unused(),
            mergeFolderById = unused(),
            removeFolderById = unused(),
            renameFolderById = unused(),
            foldersRouteFactory = FoldersRouteFactoryDefault,
            vaultRouteFactory = VaultRouteFactoryDefault,
        ).first()
        return assertNotNull(state.content.getOrNull())
            .items
            .filterIsInstance<FoldersState.Content.Item.Folder>()
    }
}

// Only the screen lifecycle, persistence and translations are faked. The test
// executes the real producer and tree builder.
private fun testScreenScope(
    scope: CoroutineScope,
): RememberStateFlowScope {
    val persisted = mutableMapOf<String, MutableStateFlow<Any?>>()
    return proxy { method, args ->
        when (method) {
            "getCoroutineContext" -> scope.coroutineContext
            "getAppScope", "getScreenScope" -> scope
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
}

private inline fun <reified T> constant(value: Any): T = proxy { method, _ ->
    check(method == "invoke") { "Unexpected ${T::class.simpleName}.$method" }
    value
}

private inline fun <reified T> unused(): T = proxy { method, _ ->
    error("Unexpected ${T::class.simpleName}.$method")
}

private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        call(method.name, args.orEmpty())
    } as T

private fun profile(
    accountId: String,
    hidden: Boolean,
) = DProfile(
    accountId = accountId,
    profileId = "profile-$accountId",
    keyBase64 = "key",
    privateKeyBase64 = "private-key",
    accountHost = "vault.example.com",
    email = "$accountId@example.com",
    emailVerified = true,
    accentColor = generateAccentColors(accountId),
    name = accountId,
    description = "",
    premium = null,
    hidden = hidden,
    securityStamp = null,
    twoFactorEnabled = null,
    masterPasswordHint = null,
    masterPasswordHintEnabled = null,
    unofficialServer = false,
    serverVersion = null,
)
