package com.artemchep.keyguard.feature.home.vault.quicksearch

import androidx.compose.runtime.compositionLocalOf

val LocalQuickSearchActivationRevision = compositionLocalOf<Int> { 0 }
val LocalQuickSearchDismiss = compositionLocalOf<(() -> Unit)?> { null }
internal const val QUICK_SEARCH_SCREEN_STATE_KEY = "quick_search_vault_list"
