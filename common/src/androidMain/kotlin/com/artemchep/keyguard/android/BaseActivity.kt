package com.artemchep.keyguard.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.MimeTypeMap
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.postDebug
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.GetAllowScreenshots
import com.artemchep.keyguard.common.usecase.GetUseExternalBrowser
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.copy.PermissionServiceAndroid
import com.artemchep.keyguard.feature.navigation.LocalNavigationBackHandler
import com.artemchep.keyguard.feature.navigation.N
import com.artemchep.keyguard.feature.navigation.NavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.NavigationRouterBackHandler
import com.artemchep.keyguard.ui.surface.LocalBackgroundManager
import com.artemchep.keyguard.ui.surface.LocalSurfaceColor
import com.artemchep.keyguard.ui.theme.KeyguardTheme
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance

abstract class BaseActivity : AppCompatActivity(), DIAware {
    override val di by closestDI()

    private val logRepository: LogRepository by instance()

    private val permissionService: PermissionServiceAndroid by instance()

    private val navTag = N.tag("BaseActivity")

    /**
     * `true` if we should open links in the external browser app,
     * `false` if we should use the chrome tab.
     */
    private var lastUseExternalBrowser: Boolean = false

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()

        val getAllowScreenshots by instance<GetAllowScreenshots>()
        getAllowScreenshots()
            .onEach { allowScreenshots ->
                if (allowScreenshots) {
                    window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            .launchIn(lifecycleScope)

        val getUseExternalBrowser: GetUseExternalBrowser by instance()
        getUseExternalBrowser()
            .onEach { useExternalBrowser ->
                lastUseExternalBrowser = useExternalBrowser
            }
            .launchIn(lifecycleScope)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            KeyguardTheme {
                val containerColor = LocalBackgroundManager.current.colorHighest
                val contentColor = contentColorFor(containerColor)
                Surface(
                    modifier = Modifier.semantics {
                        // Allows to use testTag() for UiAutomator's resource-id.
                        // It can be enabled high in the compose hierarchy,
                        // so that it's enabled for the whole subtree
                        testTagsAsResourceId = true
                    },
                    color = containerColor,
                    contentColor = contentColor,
                ) {
                    CompositionLocalProvider(
                        LocalSurfaceColor provides containerColor,
                    ) {
                        Navigation {
                            Content()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Navigation(
        block: @Composable () -> Unit,
    ) = NavigationRouterBackHandler(
        onBackPressedDispatcher = onBackPressedDispatcher,
    ) {
        val showMessage by rememberInstance<ShowMessage>()
        NavigationController(
            scope = lifecycleScope,
            canPop = flowOf(false),
            handle = { intent ->
                handleNavigationIntent(
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
        intent: NavigationIntent,
        showMessage: ShowMessage,
    ) = kotlin.run {
        when (intent) {
            is NavigationIntent.NavigateToPreview -> handleNavigationIntent(intent, showMessage)
            is NavigationIntent.NavigateToPreviewInFileManager -> handleNavigationIntent(
                intent,
                showMessage,
            )

            is NavigationIntent.NavigateToSend -> handleNavigationIntent(intent, showMessage)
            is NavigationIntent.NavigateToLargeType -> handleNavigationIntent(intent, showMessage)
            is NavigationIntent.NavigateToShare -> handleNavigationIntent(intent, showMessage)
            is NavigationIntent.NavigateToApp -> handleNavigationIntent(intent, showMessage)
            is NavigationIntent.NavigateToPhone -> handleNavigationIntent(intent, showMessage)
            is NavigationIntent.NavigateToSms -> handleNavigationIntent(intent, showMessage)
            is NavigationIntent.NavigateToEmail -> handleNavigationIntent(intent, showMessage)
            is NavigationIntent.NavigateToBrowser ->
                if (lastUseExternalBrowser) {
                    handleNavigationIntentExternalBrowser(intent, showMessage)
                } else {
                    handleNavigationIntentInAppBrowser(intent, showMessage)
                }
            // Should never be called, because we should disable
            // custom back button handling if we have nothing to
            // handle.
            is NavigationIntent.Pop -> {
                val msg = "Called Activity.finish() manually. We should have stopped " +
                        "intercepting back button presses."
                logRepository.post(navTag, msg, level = LogLevel.WARNING)
                supportFinishAfterTransition()
            }

            else -> return@run intent
        }
        null // handled
    }

    private fun handleNavigationIntent(
        intent: NavigationIntent.NavigateToPreview,
        showMessage: ShowMessage,
    ) {
        logRepository.postDebug(navTag) {
            "Navigating to preview '${intent.uri}' with a file name set to " +
                    "'${intent.fileName}'"
        }
        kotlin.runCatching {
            val launchIntent = Intent(Intent.ACTION_VIEW).apply {
                populateFileData(
                    uri = intent.uri,
                    fileName = intent.fileName,
                )
            }
            launchIntent.let(::startActivity)
        }.onFailure { e ->
            showMessage.internalShowNavigationErrorMessage(e)
        }
    }

    private fun handleNavigationIntent(
        intent: NavigationIntent.NavigateToPreviewInFileManager,
        showMessage: ShowMessage,
    ) {
        logRepository.postDebug(navTag) {
            "Navigating to preview in file manager '${intent.uri}' with a file name set to " +
                    "'${intent.fileName}'"
        }
        kotlin.runCatching {
            // TODO:
        }.onFailure { e ->
            showMessage.internalShowNavigationErrorMessage(e)
        }
    }

    private fun handleNavigationIntent(
        intent: NavigationIntent.NavigateToSend,
        showMessage: ShowMessage,
    ) {
        logRepository.postDebug(navTag) {
            "Navigating to send '${intent.uri}' with a file name set to " +
                    "'${intent.fileName}'"
        }
        kotlin.runCatching {
            val launchIntent = Intent(Intent.ACTION_SEND).apply {
                populateFileData(
                    uri = intent.uri,
                    fileName = intent.fileName,
                )
            }
            launchIntent.let(::startActivity)
        }.onFailure { e ->
            showMessage.internalShowNavigationErrorMessage(e)
        }
    }

    private fun handleNavigationIntent(
        intent: NavigationIntent.NavigateToLargeType,
        showMessage: ShowMessage,
    ) {
        logRepository.postDebug(navTag) {
            "Navigating to large type."
        }

        val launchIntent = LargeTypeActivity.getIntent(
            context = this,
            args = LargeTypeActivity.Args(
                phrases = intent.phrases,
                colorize = intent.colorize,
            ),
        )
        kotlin.runCatching {
            startActivity(launchIntent)

            // If everything is fine, obtain the session
            // and lock the vault
            val windowCoroutineScope by instance<WindowCoroutineScope>()
            val getVaultSession by instance<GetVaultSession>()
            getVaultSession()
                .toIO()
                .effectTap {
                    // Introduce a little delay, so we lock the vault after
                    // the navigation animation is complete.
                    delay(300L)
                }
                .flatMap { session ->
                    when (session) {
                        is MasterSession.Key -> {
                            val lockVault by session.di.instance<ClearVaultSession>()
                            lockVault()
                        }

                        is MasterSession.Empty -> ioUnit()
                    }
                }
                .launchIn(windowCoroutineScope)
        }.onFailure { e ->
            showMessage.internalShowNavigationErrorMessage(e)
        }
    }

    private fun handleNavigationIntent(
        intent: NavigationIntent.NavigateToShare,
        showMessage: ShowMessage,
    ) {
        logRepository.postDebug(navTag) {
            "Navigating to share."
        }

        kotlin.runCatching {
            val launchIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, intent.text)
            }
            val chooserIntent = Intent.createChooser(launchIntent, "Share")
            startActivity(chooserIntent)
        }.onFailure { e ->
            showMessage.internalShowNavigationErrorMessage(e)
        }
    }

    private fun Intent.populateFileData(
        uri: String,
        fileName: String?,
    ) {
        val file = Uri.parse(uri).toFile()
        val type = fileName?.substringAfterLast('.')
            ?.lowercase()
            ?.let { ext ->
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            }
            ?: "*/*"

        val contentUri = kotlin.run {
            val context = this@BaseActivity
            val authority = "$packageName.fileProvider"
            // if possible specify desired file name
            if (fileName != null) {
                FileProvider.getUriForFile(context, authority, file, fileName)
            } else {
                FileProvider.getUriForFile(context, authority, file)
            }
        }
        putExtra(Intent.EXTRA_STREAM, contentUri)
        setDataAndType(contentUri, type)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        if (fileName != null) {
            putExtra(Intent.EXTRA_SUBJECT, fileName)
        }
    }

    private fun handleNavigationIntent(
        intent: NavigationIntent.NavigateToApp,
        showMessage: ShowMessage,
    ) {
        logRepository.postDebug(navTag) {
            "Navigating to app '${intent.packageName}'"
        }
        kotlin.runCatching {
            val launchIntent = packageManager
                .getLaunchIntentForPackage(intent.packageName)
            launchIntent?.let(::startActivity)
        }.onFailure { e ->
            showMessage.internalShowNavigationErrorMessage(e)
        }
    }

    private fun handleNavigationIntent(
        intent: NavigationIntent.NavigateToPhone,
        showMessage: ShowMessage,
    ) {
        logRepository.postDebug(navTag) {
            "Navigating to call '${intent.phoneNumber}'"
        }
        kotlin.runCatching {
            val launchIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:${intent.phoneNumber}")
            }
            startActivity(launchIntent)
        }.onFailure { e ->
            showMessage.internalShowNavigationErrorMessage(e)
        }
    }

    private fun handleNavigationIntent(
        intent: NavigationIntent.NavigateToSms,
        showMessage: ShowMessage,
    ) {
        logRepository.postDebug(navTag) {
            "Navigating to message '${intent.phoneNumber}'"
        }
        kotlin.runCatching {
            val launchIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("sms:${intent.phoneNumber}")
            }
            startActivity(launchIntent)
        }.onFailure { e ->
            showMessage.internalShowNavigationErrorMessage(e)
        }
    }

    private fun handleNavigationIntent(
        intent: NavigationIntent.NavigateToEmail,
        showMessage: ShowMessage,
    ) {
        logRepository.postDebug(navTag) {
            "Navigating to email '${intent.email}'"
        }
        kotlin.runCatching {
            val launchIntent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(intent.email))
                intent.subject?.let {
                    putExtra(Intent.EXTRA_SUBJECT, it)
                }
                intent.body?.let {
                    putExtra(Intent.EXTRA_TEXT, it)
                }
                // Show only email clients in the list!
                selector = kotlin.run {
                    Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:")
                    }
                }
            }
            startActivity(launchIntent)
        }.onFailure { e ->
            showMessage.internalShowNavigationErrorMessage(e)
        }
    }

    private fun handleNavigationIntentInAppBrowser(
        intent: NavigationIntent.NavigateToBrowser,
        showMessage: ShowMessage,
    ) {
        logRepository.postDebug(navTag) {
            "Navigating to browser '${intent.url}'"
        }
        kotlin.runCatching {
            val parsedUrl = Uri.parse(intent.url)
            try {
                val customTabs = CustomTabsIntent.Builder()
                    .build()
                customTabs.launchUrl(this, parsedUrl)
            } catch (e: ActivityNotFoundException) {
                Intent(Intent.ACTION_VIEW, parsedUrl)
                    // launch activity
                    .let(::startActivity)
            }
        }.onFailure { e ->
            showMessage.internalShowNavigationErrorMessage(e)
        }
    }

    private fun handleNavigationIntentExternalBrowser(
        intent: NavigationIntent.NavigateToBrowser,
        showMessage: ShowMessage,
    ) {
        logRepository.postDebug(navTag) {
            "Navigating to browser '${intent.url}'"
        }
        kotlin.runCatching {
            val parsedUrl = Uri.parse(intent.url)
            Intent(Intent.ACTION_VIEW, parsedUrl)
                // launch activity
                .let(::startActivity)
        }.onFailure { e ->
            showMessage.internalShowNavigationErrorMessage(e)
        }
    }

    private fun ShowMessage.internalShowNavigationErrorMessage(e: Throwable) {
        Firebase.crashlytics.recordException(e)

        e.printStackTrace()

        val model = ToastMessage(
            type = ToastMessage.Type.ERROR,
            title = when (e) {
                is ActivityNotFoundException -> "No installed app can handle this request."
                else -> "Something went wrong"
            },
        )
        copy(model)
    }

    @Composable
    protected abstract fun Content()

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionService.refresh()
    }
}
