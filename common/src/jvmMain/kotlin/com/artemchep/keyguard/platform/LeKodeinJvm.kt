package com.artemchep.keyguard.platform

import org.kodein.di.DirectDI
import org.kodein.di.allInstances

actual inline fun <reified T : Any> DirectDI.leAllInstances(): List<T> =
    allInstances()
