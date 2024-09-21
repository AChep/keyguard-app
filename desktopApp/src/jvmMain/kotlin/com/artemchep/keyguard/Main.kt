package com.artemchep.keyguard

import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.artemchep.keyguard.common.AppWorker
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.PersistedSession
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.googleauthenticator.model.OtpAuthMigrationData
import com.artemchep.keyguard.common.service.session.VaultSessionLocker
import com.artemchep.keyguard.common.service.vault.KeyReadWriteRepository
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCloseToTray
import com.artemchep.keyguard.common.usecase.GetLocale
import com.artemchep.keyguard.common.usecase.GetVaultPersist
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.PutVaultSession
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.worker.Wrker
import com.artemchep.keyguard.core.session.diFingerprintRepositoryModule
import com.artemchep.keyguard.desktop.util.navigateToBrowser
import com.artemchep.keyguard.desktop.util.navigateToEmail
import com.artemchep.keyguard.desktop.util.navigateToFile
import com.artemchep.keyguard.desktop.util.navigateToFileInFileManager
import com.artemchep.keyguard.feature.favicon.Favicon
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.feature.keyguard.AppRoute
import com.artemchep.keyguard.feature.navigation.LocalNavigationBackHandler
import com.artemchep.keyguard.feature.navigation.NavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.feature.navigation.NavigationRouterBackHandler
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.lifecycle.LaunchLifecycleProviderEffect
import com.artemchep.keyguard.platform.lifecycle.LeLifecycleState
import com.artemchep.keyguard.platform.lifecycle.LePlatformLifecycleProvider
import com.artemchep.keyguard.platform.lifecycle.onState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.LocalComposeWindow
import com.artemchep.keyguard.ui.surface.LocalBackgroundManager
import com.artemchep.keyguard.ui.surface.LocalSurfaceColor
import com.artemchep.keyguard.ui.theme.KeyguardTheme
import io.kamel.core.config.KamelConfig
import io.kamel.core.config.takeFrom
import io.kamel.core.mapper.Mapper
import io.kamel.image.config.Default
import io.kamel.image.config.LocalKamelConfig
import io.ktor.http.Url
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.schema.ProtoBufSchemaGenerator
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DI
import org.kodein.di.allInstances
import org.kodein.di.bindSingleton
import org.kodein.di.compose.rememberInstance
import org.kodein.di.compose.withDI
import org.kodein.di.direct
import org.kodein.di.instance
import java.security.Security
import java.util.Locale
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.reflect.KClass

@OptIn(ExperimentalEncodingApi::class)
fun main() {
    // Add BouncyCastle as the first security provider
    // to make OkHTTP use its TLS instead of a platform
    // specific one.
    // https://github.com/square/okhttp?tab=readme-ov-file#requirements
    Security.insertProviderAt(BouncyCastleProvider(), 1)
    Security.insertProviderAt(BouncyCastleJsseProvider(), 2)

    val g = ProtoBufSchemaGenerator.generateSchemaText(listOf(OtpAuthMigrationData.serializer().descriptor))
    println("help!")
    println(g)
    println("help!")
    val f = ProtoBuf.decodeFromByteArray<OtpAuthMigrationData>(Base64.decode("Cj4KCpgu67BGYPX7QLkSH01pY3Jvc29mdDphcnRlbWNoZXBAb3V0bG9vay5jb20aCU1pY3Jvc29mdCABKAEwAgoxChRAhdYwy3lUa0se1A2wvpXq5vZnlxIJYXJ0ZW1jaGVwGghGYWNlYm9vayABKAEwAhABGAEgACjeyrmK/f////8B"))
    println(f)

    val kamelConfig = KamelConfig {
        this.takeFrom(KamelConfig.Default)
        mapper(FaviconUrlMapper)
    }
    val appDi = DI.invoke {
        import(diFingerprintRepositoryModule())
        bindSingleton {
            kamelConfig
        }
    }

    val processLifecycleProvider = LePlatformLifecycleProvider(
        scope = GlobalScope,
        cryptoGenerator = appDi.direct.instance(),
    )

    val getVaultSession by appDi.di.instance<GetVaultSession>()
    val putVaultSession by appDi.di.instance<PutVaultSession>()
    val getVaultPersist by appDi.di.instance<GetVaultPersist>()
    val keyReadWriteRepository by appDi.di.instance<KeyReadWriteRepository>()
    getVaultSession()
        .map { session ->
            val key = session as? MasterSession.Key
            key?.di?.direct?.instance<GetAccounts>()
        }
        .mapLatest { getAccounts ->
            if (getAccounts != null) {
                coroutineScope {
                    Favicon.launch(this, getAccounts)
                }
            }
        }
        .launchIn(GlobalScope)

    // locale
    val systemLocale = Locale.getDefault()
    val getLocale by appDi.di.instance<GetLocale>()
    getLocale()
        .onEach { locale ->
            val newLocale = when (locale) {
                null -> systemLocale
                else -> {
                    val parts = locale.split("-")
                    when {
                        parts.size == 2 -> {
                            val language = parts[0]
                            val country = parts[1]
                            Locale(language, country)
                        }

                        else -> Locale(locale)
                    }
                }
            }
            Locale.setDefault(newLocale)
        }
        .launchIn(GlobalScope)

    // persist
    combine(
        getVaultSession(),
        getVaultPersist(),
    ) { session, persist ->
        val persistedMasterKey = if (persist && session is MasterSession.Key) {
            session.masterKey
        } else {
            null
        }
        persistedMasterKey
    }
        .onEach { masterKey ->
            val persistedSession = if (masterKey != null) {
                PersistedSession(
                    masterKey = masterKey,
                    createdAt = Clock.System.now(),
                    persistedAt = Clock.System.now(),
                )
            } else {
                null
            }
            keyReadWriteRepository.put(persistedSession)
                .attempt()
                .bind()
        }
        .launchIn(GlobalScope)

    val appWorker by appDi.di.instance<AppWorker>(tag = AppWorker.Feature.SYNC)
    val processLifecycleFlow = MutableStateFlow(LeLifecycleState.RESUMED)
    GlobalScope.launch {
        appWorker.launch(this, processLifecycleFlow)
    }

    val workers by appDi.di.allInstances<Wrker>()
    GlobalScope.launch {
        workers.forEach {
            it.start(this, processLifecycleFlow)
        }
    }

    // timeout
    val vaultSessionLocker: VaultSessionLocker by appDi.di.instance()
    processLifecycleProvider.lifecycleStateFlow
        .onState(minActiveState = LeLifecycleState.RESUMED) {
            vaultSessionLocker.keepAlive()
        }
        .launchIn(GlobalScope)

    val getCloseToTray: GetCloseToTray = appDi.direct.instance()

    application(exitProcessOnExit = true) {
        withDI(appDi) {
            val isWindowOpenState = remember {
                mutableStateOf(true)
            }
            val onWindowOpen = remember(isWindowOpenState) {
                // lambda
                {
                    isWindowOpenState.value = true
                }
            }

            // Show a tray icon and allow the app to be collapsed into
            // the tray on supported platforms.
            val getCloseToTrayState = if (isTraySupported) {
                remember { getCloseToTray() }
                    .collectAsState(false)
            } else {
                // If the tray is not supported then we
                // can never close to it.
                remember {
                    mutableStateOf(false)
                }
            }
            if (getCloseToTrayState.value) {
                val trayState = rememberTrayState()
                Tray(
                    icon = when (CurrentPlatform) {
                        is Platform.Desktop.MacOS -> painterResource(Res.drawable.ic_tray_macos)
                        else -> painterResource(Res.drawable.ic_keyguard)
                    },
                    state = trayState,
                    onAction = onWindowOpen,
                    menu = {
                        Item(
                            stringResource(Res.string.show_keyguard),
                            onClick = onWindowOpen,
                        )
                        Item(
                            stringResource(Res.string.quit),
                            onClick = ::exitApplication,
                        )
                    },
                )
            } else {
                isWindowOpenState.value = true
            }
            if (isWindowOpenState.value) {
                KeyguardWindow(
                    processLifecycleProvider = processLifecycleProvider,
                    onCloseRequest = {
                        val shouldCloseToTray = getCloseToTrayState.value
                        if (shouldCloseToTray) {
                            isWindowOpenState.value = false
                        } else {
                            exitApplication()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ApplicationScope.KeyguardWindow(
    processLifecycleProvider: LePlatformLifecycleProvider,
    onCloseRequest: () -> Unit,
) {
    val windowState = rememberWindowState()
    Window(
        onCloseRequest = onCloseRequest,
        icon = painterResource(Res.drawable.ic_keyguard),
        state = windowState,
        title = "Keyguard",
    ) {
        LaunchLifecycleProviderEffect(
            processLifecycleProvider = processLifecycleProvider,
        )

        KeyguardTheme {
            val containerColor = LocalBackgroundManager.current.colorHighest
            val containerColorAnimatedState = animateColorAsState(containerColor)
            val contentColor = contentColorFor(containerColor)
            Surface(
                modifier = Modifier.semantics {
                    // Allows to use testTag() for UiAutomator's resource-id.
                    // It can be enabled high in the compose hierarchy,
                    // so that it's enabled for the whole subtree
                    // testTagsAsResourceId = true
                },
                color = containerColorAnimatedState.value,
                contentColor = contentColor,
            ) {
                val kamelConfig by rememberInstance<KamelConfig>()
                CompositionLocalProvider(
                    LocalSurfaceColor provides containerColor,
                    LocalComposeWindow provides this.window,
                    LocalKamelConfig provides kamelConfig,
                ) {
                    Navigation(
                        exitApplication = ::exitApplication,
                    ) {
                        Content()
                    }
                }
            }
        }
    }
}

internal val FaviconUrlMapper: Mapper<FaviconUrl, Url> = object : Mapper<FaviconUrl, Url> {
    override val inputKClass: KClass<FaviconUrl>
        get() = FaviconUrl::class
    override val outputKClass: KClass<Url>
        get() = Url::class

    override fun map(input: FaviconUrl): Url = kotlin.run {
        val siteUrl = input.url
        val finalUrl = kotlin.run {
            val server = Favicon.getServerOrNull(input.serverId)
            server?.transform(siteUrl)
        }
        finalUrl?.let(::Url)
            ?: Url("https://example.com")
    }
}

@Composable
private fun Content() {
    CompositionLocalProvider(
        LocalAppMode provides AppMode.Main,
    ) {
        NavigationNode(
            id = "App:Main",
            route = AppRoute,
        )
    }
}

@Composable
private fun Navigation(
    exitApplication: () -> Unit,
    block: @Composable () -> Unit,
) = NavigationRouterBackHandler(
    sideEffect = { backHandler ->
    },
) {
    val showMessage by rememberInstance<ShowMessage>()
    NavigationController(
        scope = GlobalScope,
        canPop = flowOf(false),
        handle = { intent ->
            handleNavigationIntent(
                exitApplication = exitApplication,
                intent = intent,
                showMessage = showMessage,
            )
        },
    ) { controller ->
        val backHandler = LocalNavigationBackHandler.current

        DisposableEffect(
            controller,
            backHandler,
        ) {
            val registration = backHandler.register(controller, emptyList())
            onDispose {
                registration()
            }
        }

        block()
    }
}

private fun handleNavigationIntent(
    exitApplication: () -> Unit,
    intent: NavigationIntent,
    showMessage: ShowMessage,
) = runCatching {
    when (intent) {
        is NavigationIntent.NavigateToPreview -> handleNavigationIntent(intent, showMessage)
        is NavigationIntent.NavigateToPreviewInFileManager -> handleNavigationIntent(intent, showMessage)
        is NavigationIntent.NavigateToSend -> handleNavigationIntent(intent, showMessage)
        is NavigationIntent.NavigateToLargeType -> handleNavigationIntent(intent, showMessage)
        is NavigationIntent.NavigateToShare -> handleNavigationIntent(intent, showMessage)
        is NavigationIntent.NavigateToApp -> handleNavigationIntent(intent, showMessage)
        is NavigationIntent.NavigateToPhone -> handleNavigationIntent(intent, showMessage)
        is NavigationIntent.NavigateToSms -> handleNavigationIntent(intent, showMessage)
        is NavigationIntent.NavigateToEmail -> handleNavigationIntent(intent, showMessage)
        is NavigationIntent.NavigateToBrowser -> handleNavigationIntent(intent, showMessage)
        // Should never be called, because we should disable
        // custom back button handling if we have nothing to
        // handle.
        is NavigationIntent.Pop -> {
            val msg = "Called Activity.finish() manually. We should have stopped " +
                    "intercepting back button presses."
            exitApplication()
        }
        // Exit.
        is NavigationIntent.Exit -> {
            exitApplication()
        }

        else -> return@runCatching intent
    }
    null // handled
}.onFailure { e ->
    showMessage.internalShowNavigationErrorMessage(e)
}.getOrNull()

private fun handleNavigationIntent(
    intent: NavigationIntent.NavigateToPreview,
    showMessage: ShowMessage,
) {
    navigateToFile(
        uri = intent.uri,
    )
}

private fun handleNavigationIntent(
    intent: NavigationIntent.NavigateToPreviewInFileManager,
    showMessage: ShowMessage,
) {
    navigateToFileInFileManager(
        uri = intent.uri,
    )
}

private fun handleNavigationIntent(
    intent: NavigationIntent.NavigateToSend,
    showMessage: ShowMessage,
) {
    TODO()
}

private fun handleNavigationIntent(
    intent: NavigationIntent.NavigateToLargeType,
    showMessage: ShowMessage,
) {
    TODO()
}

private fun handleNavigationIntent(
    intent: NavigationIntent.NavigateToShare,
    showMessage: ShowMessage,
) {
    TODO()
}

private fun handleNavigationIntent(
    intent: NavigationIntent.NavigateToApp,
    showMessage: ShowMessage,
) {
    TODO()
}

private fun handleNavigationIntent(
    intent: NavigationIntent.NavigateToPhone,
    showMessage: ShowMessage,
) {
    TODO()
}

private fun handleNavigationIntent(
    intent: NavigationIntent.NavigateToSms,
    showMessage: ShowMessage,
) {
    TODO()
}

private fun handleNavigationIntent(
    intent: NavigationIntent.NavigateToEmail,
    showMessage: ShowMessage,
) {
    navigateToEmail(
        email = intent.email,
        subject = intent.subject,
        body = intent.body,
    )
}

private fun handleNavigationIntent(
    intent: NavigationIntent.NavigateToBrowser,
    showMessage: ShowMessage,
) {
    navigateToBrowser(
        uri = intent.url,
    )
}

private fun ShowMessage.internalShowNavigationErrorMessage(e: Throwable) {
    e.printStackTrace()

    val model = ToastMessage(
        type = ToastMessage.Type.ERROR,
        title = when (e) {
            else -> "Something went wrong"
        },
    )
    copy(model)
}
