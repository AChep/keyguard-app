package com.artemchep.keyguard.util.signalr.logger

fun interface Logger {
    fun log(
        severity: Severity,
        message: String,
        cause: Throwable?,
    )

    enum class Severity {
        INFO,
        WARNING,
        ERROR,
    }

    companion object {
        val Empty = Logger { _, _, _ -> }
    }
}