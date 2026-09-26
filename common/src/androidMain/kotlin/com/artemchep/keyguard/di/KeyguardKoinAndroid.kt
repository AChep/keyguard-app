package com.artemchep.keyguard.di

import android.content.Context
import org.koin.core.Koin

/** Resolves the application-owned graph without a process-global Koin registry. */
fun Context.keyguardKoin(): Koin =
    (applicationContext as KeyguardKoinOwner).koin
