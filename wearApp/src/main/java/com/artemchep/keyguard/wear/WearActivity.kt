package com.artemchep.keyguard.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material3.MaterialTheme
import com.artemchep.keyguard.android.BaseApp
import com.artemchep.keyguard.copy.PermissionServiceAndroid
import com.artemchep.keyguard.di.KeyguardKoinOwner
import com.artemchep.keyguard.di.keyguardKoin
import com.artemchep.keyguard.ui.surface.LocalSurfaceColor
import com.artemchep.keyguard.wear.ui.WearKeyguardTheme
import kotlin.getValue
import org.koin.compose.KoinIsolatedContext

class WearActivity : ComponentActivity(), KeyguardKoinOwner {
    override val koin get() = keyguardKoin()

    private val permissionService: PermissionServiceAndroid by lazy { koin.get() }

    override fun onResume() {
        super.onResume()
        permissionService.refresh()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionService.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KoinIsolatedContext((application as BaseApp).koinApplication) {
                WearKeyguardTheme {
                    val containerColor = MaterialTheme.colorScheme.background
                    Box(
                        modifier = Modifier
                            .background(containerColor)
                            .semantics {
                                // Allows to use testTag() for UiAutomator's resource-id.
                                // It can be enabled high in the compose hierarchy,
                                // so that it's enabled for the whole subtree
                                testTagsAsResourceId = true
                            },
                    ) {
                        CompositionLocalProvider(
                            LocalSurfaceColor provides containerColor,
                        ) {
                            WearNavigationHost(
                                onBackPressedDispatcher = onBackPressedDispatcher,
                                scope = lifecycleScope,
                            ) {
                                WearRoot()
                            }
                        }
                    }
                }
            }
        }
    }
}
