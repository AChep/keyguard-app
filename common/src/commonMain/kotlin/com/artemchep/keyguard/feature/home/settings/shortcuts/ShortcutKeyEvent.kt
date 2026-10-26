package com.artemchep.keyguard.feature.home.settings.shortcuts

import androidx.compose.ui.input.key.KeyEvent

/** AltGr types characters; a global shortcut must not capture it as Ctrl+Alt. */
internal expect val KeyEvent.isAltGraphPressed: Boolean
