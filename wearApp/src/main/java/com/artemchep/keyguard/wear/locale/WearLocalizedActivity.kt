package com.artemchep.keyguard.wear.locale

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.artemchep.keyguard.di.KeyguardKoinOwner
import com.artemchep.keyguard.di.keyguardKoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

abstract class WearLocalizedActivity : ComponentActivity(), KeyguardKoinOwner {
    override val koin get() = keyguardKoin()

    private lateinit var localeController: WearLocaleController
    private var appliedLocales: List<String>? = null

    override fun attachBaseContext(newBase: Context) {
        localeController = newBase.keyguardKoin().get()
        // On the framework path the OS applies app locales to the activity itself, and
        // Application.onConfigurationChanged keeps the shared state current.
        val base = if (localeController.usesFrameworkLocales) {
            newBase
        } else {
            localeController.refresh()
            val locales = localeController.state.value.locales
            appliedLocales = locales
            newBase.withWearLocales(locales)
        }
        super.attachBaseContext(base)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (localeController.usesFrameworkLocales) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                localeController.state.first { it.locales != appliedLocales }
                if (!isFinishing) recreate()
            }
        }
    }
}
