package com.artemchep.keyguard

import androidx.compose.animation.animateColorAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import arrow.core.throwIfFatal
import coil3.SingletonImageLoader
import com.artemchep.keyguard.common.AppWorker
import com.artemchep.keyguard.common.di.imageLoaderModule
import com.artemchep.keyguard.common.di.setFromDi
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.PersistedSession
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.app.AppIconFetcher
import com.artemchep.keyguard.common.service.app.AppIconKeyer
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.keyboard.KeyboardShortcutsService
import com.artemchep.keyguard.common.service.keychain.KeychainRepository
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.notification.NotificationRepository
import com.artemchep.keyguard.common.service.session.VaultSessionLocker
import com.artemchep.keyguard.common.service.sshagent.SshAgentStatusService
import com.artemchep.keyguard.common.service.vault.KeyReadWriteRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentManager
import com.artemchep.keyguard.common.model.SshAgentStatus
import com.artemchep.keyguard.feature.sshagent.rememberSshAgentRequestUiState
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCloseToTray
import com.artemchep.keyguard.common.usecase.GetLocale
import com.artemchep.keyguard.common.usecase.GetSshAgent
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultPersist
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.PutVaultSession
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.worker.Wrker
import com.artemchep.keyguard.core.session.diFingerprintRepositoryModule
import com.artemchep.keyguard.desktop.WindowStateManager
import com.artemchep.keyguard.desktop.services.keychain.KeychainRepositoryNative
import com.artemchep.keyguard.desktop.services.notification.NotificationRepositoryNative
import com.artemchep.keyguard.desktop.ui.SshRequestWindow
import com.artemchep.keyguard.desktop.util.AppReopenedListenerEffect
import com.artemchep.keyguard.desktop.util.handleNavigationIntent
import com.artemchep.keyguard.feature.favicon.Favicon
import com.artemchep.keyguard.feature.keyguard.AppRoute
import com.artemchep.keyguard.feature.loading.getErrorReadableMessage
import com.artemchep.keyguard.feature.navigation.LocalNavigationBackHandler
import com.artemchep.keyguard.feature.navigation.NavigationController
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.feature.navigation.NavigationRouterBackHandler
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.platform.LeContext
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
import com.kdroid.composetray.tray.api.Tray
import com.kdroid.composetray.utils.SingleInstanceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlin.time.Clock
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
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
fun main() {
    // Add BouncyCastle as the first security provider
    // to make OkHTTP use its TLS instead of a platform
    // specific one.
    // https://github.com/square/okhttp?tab=readme-ov-file#requirements
    Security.insertProviderAt(BouncyCastleProvider(), 1)
    Security.insertProviderAt(BouncyCastleJsseProvider(), 2)

    // Allow the app to use system default proxies:
    // https://docs.oracle.com/javase/8/docs/technotes/guides/net/proxies.html
    System.setProperty("java.net.useSystemProxies", "true")

    val appDi = DI.invoke {
        import(diFingerprintRepositoryModule())

        val imageLoaderModule = imageLoaderModule { directDI ->
            val appIconFactory = AppIconFetcher.Factory(
                googlePlayParser = directDI.instance(),
                getWebsiteIcons = directDI.instance(),
            )
            add(appIconFactory)
            add(AppIconKeyer())
        }
        import(imageLoaderModule)
        bindSingleton {
            WindowStateManager(this)
        }
        bindSingleton<KeychainRepository> {
            KeychainRepositoryNative(
                directDI = this,
            )
        }
        bindSingleton<NotificationRepository> {
            NotificationRepositoryNative(
                directDI = this,
            )
        }
    }

    // Construct the image loader singleton to match what
    // we have set in the application's DI.
    SingletonImageLoader.setFromDi(appDi)

    val processLifecycleProvider = LePlatformLifecycleProvider(
        scope = GlobalScope,
        cryptoGenerator = appDi.direct.instance(),
    )

    val logRepository by appDi.di.instance<LogRepository>()
    val cryptoGenerator by appDi.di.instance<CryptoGenerator>()
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
    val getSshAgent: GetSshAgent = appDi.direct.instance()
    val getSshAgentFilter: GetSshAgentFilter = appDi.direct.instance()
    val sshAgentStatusService: SshAgentStatusService = appDi.direct.instance()

    val translatorScope by lazy {
        val context = LeContext()
        TranslatorScope.of(context)
    }

    val windowStateManager by appDi.di.instance<WindowStateManager>()
    application(exitProcessOnExit = true) {
        withDI(appDi) {
            val isWindowOpenState = remember {
                mutableStateOf(true)
            }
            val onWindowOpen = remember(isWindowOpenState) {
                // lambda
                {
                    isWindowOpenState.value = true
                    // If the window is minimized then clicking
                    // the tray should also bring the window back
                    // to the front.
                    windowStateManager.requestForeground()
                }
            }

            // Single instance check - restore existing window
            // if another instance is attempted.
            val isSingleInstance = SingleInstanceManager.isSingleInstance(
                onRestoreRequest = {
                    onWindowOpen()
                },
            )
            if (!isSingleInstance) {
                exitApplication()
                return@withDI
            }

            // SSH Agent: Start the SSH agent if the binary is available.
            // The binary is bundled in the app resources during distribution.
            // This is placed after the single-instance check so that a
            // second app instance never touches the shared SSH socket.
            val sshAgentManager = remember {
                SshAgentManager(
                    logRepository = logRepository,
                    cryptoGenerator = cryptoGenerator,
                    getVaultSession = getVaultSession,
                    getSshAgentFilter = getSshAgentFilter,
                )
            }
            val showMessage by rememberInstance<ShowMessage>()
            val getSshAgentState = remember { getSshAgent() }
                .collectAsState(false)
            LaunchedEffect(
                sshAgentManager,
                getSshAgentState.value,
            ) {
                val binaryPath = sshAgentManager.defaultBinaryPath
                if (binaryPath == null) {
                    sshAgentStatusService.set(SshAgentStatus.Unsupported)
                    return@LaunchedEffect
                }
                if (!getSshAgentState.value) {
                    sshAgentStatusService.set(SshAgentStatus.Stopped)
                    return@LaunchedEffect
                }

                coroutineScope {
                    var failed = false
                    try {
                        sshAgentStatusService.set(SshAgentStatus.Starting)
                        val process = sshAgentManager.start(
                            scope = this,
                            binaryPath = binaryPath,
                        )
                        sshAgentStatusService.set(SshAgentStatus.Ready)
                        val exitCode = runInterruptible(Dispatchers.IO) {
                            process.waitFor()
                        }
                        throw IllegalStateException("SSH agent process exited unexpectedly: $exitCode")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.throwIfFatal()
                        e.printStackTrace()
                        failed = true
                        sshAgentStatusService.set(SshAgentStatus.Failed)

                        val text = getErrorReadableMessage(e, translatorScope)
                            .title
                        val msg = ToastMessage(
                            type = ToastMessage.Type.ERROR,
                            title = translatorScope.translate(Res.string.error_failed_ssh_agent_start),
                            text = text,
                        )
                        showMessage.copy(msg)
                    } finally {
                        withContext(NonCancellable) {
                            sshAgentManager.stop()
                        }
                        if (!failed) {
                            sshAgentStatusService.set(SshAgentStatus.Stopped)
                        }
                    }
                }
            }
            // We want to collect all the requests into
            // a single model.
            val sshAgentRequestUiState = rememberSshAgentRequestUiState(
                requestsFlow = sshAgentManager.requestsFlow,
            )
            if (sshAgentRequestUiState != null) {
                SshRequestWindow(
                    processLifecycleProvider = processLifecycleProvider,
                    sshAgentRequestUiState = sshAgentRequestUiState,
                )
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
                val showKeyguardLabel = stringResource(Res.string.show_keyguard)
                val quitLabel = stringResource(Res.string.quit)
                Tray(
                    windowsIcon = painterResource(Res.drawable.ic_keyguard),
                    macLinuxIcon = Icons.Outlined.Lock,
                    tooltip = "Keyguard",
                    primaryAction = onWindowOpen,
                ) {
                    Item(
                        label = showKeyguardLabel,
                    ) { onWindowOpen() }
                    Divider()
                    Item(
                        label = quitLabel,
                    ) {
                        dispose()
                        exitApplication()
                    }
                }
            } else {
                isWindowOpenState.value = true
            }
            KeyguardMainWindow(
                processLifecycleProvider = processLifecycleProvider,
                stateManager = windowStateManager,
                visible = isWindowOpenState.value,
                onReopenRequest = onWindowOpen,
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

@Composable
private fun ApplicationScope.KeyguardMainWindow(
    processLifecycleProvider: LePlatformLifecycleProvider,
    stateManager: WindowStateManager,
    visible: Boolean,
    onReopenRequest: () -> Unit,
    onCloseRequest: () -> Unit,
) {
    KeyguardMainWindow(
        processLifecycleProvider = processLifecycleProvider,
        stateManager = stateManager,
        visible = visible,
        onCloseRequest = onCloseRequest,
    ) {
        window.toFront()

        // If you click on a closed to tray app's icon then
        // that will trigger an event here.
        AppReopenedListenerEffect {
            onReopenRequest()
        }

        KeyguardTheme {
            KeyguardWindowScaffold {
                Content()
            }
        }
    }
}

@Composable
private fun ApplicationScope.KeyguardMainWindow(
    processLifecycleProvider: LePlatformLifecycleProvider,
    stateManager: WindowStateManager,
    visible: Boolean,
    onCloseRequest: () -> Unit,
    content: @Composable FrameWindowScope.() -> Unit,
) {
    val state = stateManager.rememberWindowState()
    val keyboardShortcutsService by rememberInstance<KeyboardShortcutsService>()
    Window(
        onCloseRequest = onCloseRequest,
        icon = painterResource(Res.drawable.ic_keyguard),
        state = state,
        visible = visible,
        title = "Keyguard",
        onKeyEvent = { event ->
            keyboardShortcutsService.handle(event)
        },
    ) {
        KeyguardWindowEssentials(
            processLifecycleProvider = processLifecycleProvider,
            content = content,
        )
    }
}

@Composable
internal fun FrameWindowScope.KeyguardWindowEssentials(
    processLifecycleProvider: LePlatformLifecycleProvider,
    content: @Composable FrameWindowScope.() -> Unit,
) {
    CompositionLocalProvider(
        LocalComposeWindow provides this.window,
    ) {
        LaunchLifecycleProviderEffect(
            processLifecycleProvider = processLifecycleProvider,
        )

        content()
    }
}

@Composable
internal fun ApplicationScope.KeyguardWindowScaffold(
    content: @Composable () -> Unit,
) {
    val containerColor = LocalBackgroundManager.current.colorHighest
    val containerColorAnimatedState = animateColorAsState(containerColor)
    val contentColor = contentColorFor(containerColor)
    Surface(
        modifier = Modifier,
        color = containerColorAnimatedState.value,
        contentColor = contentColor,
    ) {
        CompositionLocalProvider(
            LocalSurfaceColor provides containerColor,
        ) {
            Navigation(
                exitApplication = ::exitApplication,
            ) {
                content()
            }
        }
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
