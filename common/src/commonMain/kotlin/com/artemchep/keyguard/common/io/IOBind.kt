package com.artemchep.keyguard.common.io

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

fun <T> IO<T>.bindBlocking(): T = runBlocking { bind() }

suspend fun <T> IO<T>.bind(): T = invoke()

suspend fun <T> List<IO<T>>.bind(context: CoroutineContext = EmptyCoroutineContext): List<T> =
    coroutineScope {
        map { io ->
            // Run all the tasks in parallel.
            async(context) {
                io.bind()
            }
        }.map { it.await() }
    }
