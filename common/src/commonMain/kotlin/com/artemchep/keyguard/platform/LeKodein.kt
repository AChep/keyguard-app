package com.artemchep.keyguard.platform

import org.kodein.di.DirectDI

expect inline fun <reified T : Any> DirectDI.leAllInstances(): List<T>
