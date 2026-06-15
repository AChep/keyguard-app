package com.artemchep.keyguard.integration.wearcredentialproviderapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material3.MaterialTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WearCredentialProviderTestTheme {
                WearCredentialProviderTestApp(
                    credentialClient = rememberWearCredentialClient(),
                )
            }
        }
    }
}

@Composable
private fun WearCredentialProviderTestTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        content = content,
    )
}
