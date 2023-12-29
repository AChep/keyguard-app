package com.artemchep.keyguard.platform

import androidx.compose.runtime.Composable

expect class LeContext

@get:Composable
expect val LocalLeContext: LeContext
