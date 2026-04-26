package com.artemchep.keyguard.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material3.MaterialTheme
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.ui.surface.LocalSurfaceColor
import com.artemchep.keyguard.wear.ui.WearKeyguardTheme
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.compose.withDI
import kotlin.getValue

abstract class WearCredentialProviderActivity : ComponentActivity(), DIAware {
    override val di by closestDI()

    val translatorScope by lazy {
        val context = LeContext(this)
        TranslatorScope.of(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            withDI(di) {
                WearKeyguardTheme {
                    val containerColor = MaterialTheme.colorScheme.background
                    Box(
                        modifier = Modifier
                            .background(containerColor),
                    ) {
                        CompositionLocalProvider(
                            LocalSurfaceColor provides containerColor,
                        ) {
                            WearNavigationHost(
                                onBackPressedDispatcher = onBackPressedDispatcher,
                                scope = lifecycleScope,
                            ) {
                                val rootNodeId = remember {
                                    "CredentialProvider:${this@WearCredentialProviderActivity.javaClass.simpleName}"
                                }
                                NavigationNode(
                                    id = rootNodeId,
                                    route = rememberCredentialProviderRoute(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun rememberCredentialProviderRoute(): Route = remember {
        object : Route {
            @Composable
            override fun Content() {
                this@WearCredentialProviderActivity.Content()
            }
        }
    }

    @Composable
    protected abstract fun Content()
}
