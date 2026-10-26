package com.artemchep.keyguard.ui.wizard

import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import com.artemchep.keyguard.ui.surface.LocalSurfaceColor
import com.artemchep.keyguard.ui.theme.GlobalExpressive
import com.artemchep.keyguard.ui.theme.LocalExpressive
import com.artemchep.keyguard.ui.theme.plainDarkColorScheme
import com.artemchep.keyguard.ui.theme.plainLightColorScheme

/** A service-free host shared by IDE previews and Desktop render checks. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WizardPreview(
    dark: Boolean = false,
    expressive: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = if (dark) plainDarkColorScheme() else plainLightColorScheme()
    MaterialExpressiveTheme(colorScheme = scheme) {
        androidx.compose.material.MaterialTheme(
            colors = (if (dark) darkColors() else lightColors()).copy(
                primary = scheme.primary,
                onPrimary = scheme.onPrimary,
                secondary = scheme.secondary,
                onSecondary = scheme.onSecondary,
            ),
        ) {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                GlobalExpressive provides expressive,
                LocalExpressive provides expressive,
                LocalSurfaceColor provides scheme.surface,
            ) {
                Surface(content = content)
            }
        }
    }
}
