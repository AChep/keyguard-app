package com.artemchep.keyguard.feature.watchtower

import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.CipherFilterContext
import com.artemchep.keyguard.common.model.CipherId
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DWatchtowerAlert
import com.artemchep.keyguard.common.model.DWatchtowerAlertType
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.model.testCipherFilterContext
import com.artemchep.keyguard.common.service.credentialexchange.cxfFolder
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.VaultRouteFactoryDefault
import com.artemchep.keyguard.feature.home.vault.folders.FoldersRouteFactoryDefault
import com.artemchep.keyguard.feature.home.vault.search.TEST_INSTANT
import com.artemchep.keyguard.feature.home.vault.search.createSecret
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.DiskHandle
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class WatchtowerArchiveTest {
    @Test
    fun `overview drops archived items and keeps trash and archived folder occupancy`() = runTest {
        val active = createSecret(
            id = "active",
            login = DSecret.Login(
                password = "shared",
                passwordStrength = PasswordStrength(crackTimeSeconds = 1L, version = 1L),
            ),
        )
        val archived = active.copy(id = "archived", folderId = "occupied", archivedDate = TEST_INSTANT)
        val trashed = active.copy(id = "trashed", deletedDate = TEST_INSTANT)
        val ciphers = MutableStateFlow(listOf(active, archived, trashed))
        val alerts = listOf(active, archived, trashed).map { cipher ->
            DWatchtowerAlert(
                alertId = cipher.id,
                cipherId = CipherId(cipher.id),
                accountId = AccountId(cipher.accountId),
                type = DWatchtowerAlertType.PWNED_PASSWORD,
                reportedAt = TEST_INSTANT,
                read = false,
                version = "old",
            )
        }
        val folders = listOf("occupied", "empty").map { id ->
            cxfFolder(id = id, name = id, accountId = active.accountId)
        }
        val navigation = mutableListOf<NavigationIntent>()
        val screen = testScreenScope(backgroundScope, navigation)
        val context = testCipherFilterContext()
        val content = screen.watchtowerContent(context, ciphers, alerts, folders)

        // The old disk counter includes archives and must not be shown on startup.
        assertEquals(Loadable.Loading, content.reused.value)
        val reused = assertNotNull(content.reused.first { it is Loadable.Ok }.getOrNull())
        assertEquals(0, reused.count)
        assertNull(reused.onClick)
        assertEquals(1, content.pwned.first { it is Loadable.Ok }.getOrNull()?.count)
        assertEquals(1, content.unreadThreats.first { it is Loadable.Ok }.getOrNull()?.count)
        assertEquals(1, content.strength.first { it is Loadable.Ok }.getOrNull()?.items?.sumOf { it.count })
        assertEquals(1, content.trashedItems.first { it is Loadable.Ok }.getOrNull()?.count)
        assertEquals(1, content.emptyItems.first { it is Loadable.Ok }.getOrNull()?.count)

        ciphers.value = listOf(active, archived.copy(archivedDate = null), trashed)
        val restored = assertNotNull(content.reused.first { it.getOrNull()?.count == 2 }.getOrNull())
        assertNotNull(restored.onClick).invoke()
        runCurrent()
        val route = (navigation.single() as NavigationIntent.NavigateToRoute).route as VaultRoute
        assertEquals(false, route.args.archive)
        assertEquals(false, route.args.trash)
        val predicate = assertNotNull(route.args.filter).prepare(context, ciphers.value)
        assertEquals(restored.count, ciphers.value.count(predicate))
    }
}

// Runs the real producer with every collaborator either constant or unused.
private suspend fun RememberStateFlowScope.watchtowerContent(
    context: CipherFilterContext,
    ciphers: Flow<List<DSecret>>,
    alerts: List<DWatchtowerAlert>,
    folders: List<DFolder>,
): WatchtowerState.Content {
    val state = watchtowerStateProducer(
        filterContext = context,
        addCipherFilter = unused(),
        confirmationRouteFactory = unused(),
        getCipherFilters = constant(flowOf(emptyList<Any>())),
        args = WatchtowerRoute.Args(),
        getCiphers = constant(ciphers),
        getAccounts = constant(flowOf(emptyList<Any>())),
        getProfiles = constant(flowOf(emptyList<Any>())),
        getFolders = constant(flowOf(folders)),
        getTags = constant(flowOf(emptyList<Any>())),
        getCollections = constant(flowOf(emptyList<Any>())),
        getOrganizations = constant(flowOf(emptyList<Any>())),
        getCheckPwnedPasswords = constant(flowOf(true)),
        getCheckPwnedServices = constant(flowOf(true)),
        getCheckTwoFA = constant(flowOf(false)),
        getCheckPasskeys = constant(flowOf(false)),
        getWatchtowerAlerts = constant(flowOf(alerts)),
        getWatchtowerUnreadAlerts = constant(flowOf(alerts)),
        cipherDuplicatesCheck = unused(),
        dismissNotificationsByChannel = unused(),
        foldersRouteFactory = FoldersRouteFactoryDefault,
        vaultRouteFactory = VaultRouteFactoryDefault,
    ).first()
    return assertNotNull(state.content.getOrNull())
}

// Only the screen lifecycle, persistence and translations are faked. The test
// executes the real producer, counters and navigation factory.
private fun testScreenScope(
    scope: CoroutineScope,
    navigation: MutableList<NavigationIntent>,
): RememberStateFlowScope {
    val disk = object : DiskHandle {
        override val restoredState = mapOf<String, Any?>(DFilter.ByPasswordDuplicates.key to 3L)
        override fun link(key: String, flow: Flow<Any?>) = Unit
        override fun unlink(key: String) = Unit
    }
    val persisted = mutableMapOf<String, MutableStateFlow<Any?>>()
    return proxy { method, args ->
        when (method) {
            "getCoroutineContext" -> scope.coroutineContext
            "getAppScope", "getScreenScope" -> scope
            "isStartedFlow" -> flowOf(false)
            "loadDiskHandle" -> disk
            "translate" -> "Test"
            "navigate" -> { navigation += args[0] as NavigationIntent; Unit }
            "action" -> {
                @Suppress("UNCHECKED_CAST")
                val action = args[0] as suspend () -> Unit
                scope.launch { action() }
                Unit
            }
            "mutablePersistedFlow" -> persisted.getOrPut(args[0] as String) {
                val storage = args[1] as PersistedStorage
                val stored = (storage as? PersistedStorage.InDisk)?.disk?.restoredState?.get(args[0])
                @Suppress("UNCHECKED_CAST")
                val initial = args.last() as () -> Any?
                val value = if (stored != null && args.size == 5) {
                    @Suppress("UNCHECKED_CAST")
                    val deserialize = args[3] as (Json, Any?) -> Any?
                    deserialize(Json, stored)
                } else initial()
                MutableStateFlow(value)
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
