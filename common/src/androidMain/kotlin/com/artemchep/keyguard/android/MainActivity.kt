package com.artemchep.keyguard.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.common.service.deeplink.DeeplinkService
import com.artemchep.keyguard.feature.keyguard.AppRoute
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.platform.recordLog
import org.kodein.di.instance

class MainActivity : BaseActivity() {
    companion object {
        fun getIntent(
            context: Context,
        ): Intent = Intent(context, MainActivity::class.java)
    }

    private val deeplinkService by instance<DeeplinkService>()

    @Composable
    override fun Content() {
        Column {
            CompositionLocalProvider(
                LocalAppMode provides AppMode.Main,
            ) {
                NavigationNode(
                    id = "App:Main",
                    route = AppRoute,
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordLog("Opened main activity")
        updateDeeplinkFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        updateDeeplinkFromIntent(intent)
    }

    private fun updateDeeplinkFromIntent(intent: Intent) {
        val customFilter = intent.getStringExtra("customFilter")
        if (customFilter != null) {
            deeplinkService.put("customFilter", customFilter)
        }

        val dta = intent.data
        if (dta != null) {
            val url = dta.toString()
            when {
                url.startsWith("bitwarden://webauthn-callback") ||
                        url.startsWith("keyguard://webauthn-callback") -> {
                    val data = dta.getQueryParameter("data")
                    deeplinkService.put("webauthn-callback", data)
                }
            }
        }
    }
}
