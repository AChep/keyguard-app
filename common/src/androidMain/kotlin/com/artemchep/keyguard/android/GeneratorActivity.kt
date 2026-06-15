package com.artemchep.keyguard.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.*
import com.artemchep.keyguard.feature.generator.GeneratorRoute
import com.artemchep.keyguard.feature.home.HomeLayout
import com.artemchep.keyguard.feature.home.LocalHomeLayout
import com.artemchep.keyguard.feature.navigation.NavigationNode
import org.kodein.di.*

class GeneratorActivity : BaseActivity(), DIAware {
    companion object {
        private const val GENERATOR_STORAGE_KEY = "generator_standalone"

        fun getIntent(
            context: Context,
        ): Intent = Intent(context, GeneratorActivity::class.java)
    }

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun Content() {
        CompositionLocalProvider(
            LocalHomeLayout provides HomeLayout.Vertical,
        ) {
            val route = GeneratorRoute(
                args = GeneratorRoute.Args(
                    username = true,
                    password = true,
                    sshKey = false,
                    storageKey = GENERATOR_STORAGE_KEY,
                ),
            )
            NavigationNode(
                id = "generator",
                route = route,
            )
        }
    }
}
