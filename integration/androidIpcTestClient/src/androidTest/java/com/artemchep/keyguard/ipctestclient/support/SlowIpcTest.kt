package com.artemchep.keyguard.ipctestclient.support

/**
 * Marks a test that waits out a provider timeout measured in tens of seconds.
 *
 * Excluded from the default `connectedDebugAndroidTest` run by the
 * `notAnnotation` runner argument in this module's build script. Opt back in
 * with `-Pandroid.testInstrumentationRunnerArguments.notAnnotation=`.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class SlowIpcTest
