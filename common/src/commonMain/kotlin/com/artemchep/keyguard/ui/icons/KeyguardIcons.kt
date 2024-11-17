package com.artemchep.keyguard.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.ui.icons.custom.Numeric
import com.artemchep.keyguard.ui.icons.custom.Symbol
import com.artemchep.keyguard.ui.icons.custom.FormatLetterCaseLower
import com.artemchep.keyguard.ui.icons.custom.FormatLetterCaseUpper
import com.artemchep.keyguard.ui.icons.custom.TwoFactorAuthentication
import kotlin.collections.List as ____KtList

public object KeyguardIcons

private var __AllIcons: ____KtList<ImageVector>? = null

public val KeyguardIcons.AllIcons: ____KtList<ImageVector>
    get() {
        if (__AllIcons != null) {
            return __AllIcons!!
        }
        __AllIcons = listOf(
            FormatLetterCaseUpper,
            Symbol,
            FormatLetterCaseLower,
            Numeric,
            TwoFactorAuthentication,
        )
        return __AllIcons!!
    }
