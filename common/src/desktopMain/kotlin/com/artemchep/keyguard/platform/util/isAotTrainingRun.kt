package com.artemchep.keyguard.platform.util

/**
 * True while the Compose Gradle plugin runs the packaged app
 * to record its AppCDS archive.
 */
val isAotTrainingRun: Boolean = System.getProperty("compose.aot.training-run") == "true"
