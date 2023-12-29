package com.artemchep.keyguard.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

operator fun PaddingValues.plus(b: PaddingValues): PaddingValues = object : PaddingValues {
    override fun calculateBottomPadding(): Dp =
        this@plus.calculateBottomPadding() + b.calculateBottomPadding()

    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        this@plus.calculateLeftPadding(layoutDirection) + b.calculateLeftPadding(layoutDirection)

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        this@plus.calculateRightPadding(layoutDirection) + b.calculateRightPadding(layoutDirection)

    override fun calculateTopPadding(): Dp =
        this@plus.calculateTopPadding() + b.calculateTopPadding()
}

operator fun PaddingValues.minus(b: PaddingValues): PaddingValues = object : PaddingValues {
    override fun calculateBottomPadding(): Dp =
        this@minus.calculateBottomPadding() - b.calculateBottomPadding()

    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        this@minus.calculateLeftPadding(layoutDirection) - b.calculateLeftPadding(layoutDirection)

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        this@minus.calculateRightPadding(layoutDirection) - b.calculateRightPadding(layoutDirection)

    override fun calculateTopPadding(): Dp =
        this@minus.calculateTopPadding() - b.calculateTopPadding()
}
