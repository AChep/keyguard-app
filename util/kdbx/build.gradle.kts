import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    id("keyguard.native-crypto-consumer")
}

kotlin {
    android {
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        namespace = "com.artemchep.keyguard.util.kdbx"

        withHostTest {}
    }
    jvm("desktop")
    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlinx.io.core)
                api(libs.squareup.okio)
                implementation(libs.xmlutil.core)
                implementation(project(":util:crypto"))
                implementation(project(":util:foundation"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val jvmCommonTest by creating {
            dependsOn(commonTest)
            dependencies {
                implementation(libs.bouncycastle.bcprov)
            }
        }
        val androidHostTest by getting {
            dependsOn(jvmCommonTest)
        }
        val desktopTest by getting {
            dependsOn(jvmCommonTest)
        }

        val jvmCommonMain by creating {
            dependsOn(commonMain)
        }
        val androidMain by getting {
            dependsOn(jvmCommonMain)
        }
        val desktopMain by getting {
            dependsOn(jvmCommonMain)
        }

        val iosMain by creating {
            dependsOn(commonMain)
        }
        val iosArm64Main by getting {
            dependsOn(iosMain)
        }
        val iosSimulatorArm64Main by getting {
            dependsOn(iosMain)
        }
        val macosMain by creating {
            dependsOn(commonMain)
        }
        val macosArm64Main by getting {
            dependsOn(macosMain)
        }

        all {
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

val desktopTestTask = tasks.named<Test>("desktopTest")
val desktopTestClassesTask = tasks.named("desktopTestClasses")

desktopTestTask.configure {
    filter {
        excludeTestsMatching("app.keemobile.kotpass.xml.benchmark.*")
        excludeTestsMatching("app.keemobile.kotpass.database.benchmark.*")
    }
}

val kdbxJfrRecording = providers.gradleProperty("kdbxJfr").orNull

fun registerKdbxBenchmark(
    name: String,
    taskDescription: String,
    testPattern: String,
) {
    tasks.register<Test>(name) {
        group = "verification"
        description = taskDescription
        dependsOn(desktopTestClassesTask)
        testClassesDirs = desktopTestTask.get().testClassesDirs
        classpath = desktopTestTask.get().classpath
        maxParallelForks = 1
        forkEvery = 0L
        outputs.upToDateWhen { false }
        kdbxJfrRecording?.let { recording ->
            jvmArgs(
                "-XX:StartFlightRecording=" +
                    "filename=$recording,settings=profile,dumponexit=true",
            )
        }
        filter {
            includeTestsMatching(testPattern)
            isFailOnNoMatchingTests = true
        }
        testLogging {
            events = setOf(
                TestLogEvent.FAILED,
                TestLogEvent.PASSED,
                TestLogEvent.STANDARD_ERROR,
                TestLogEvent.STANDARD_OUT,
            )
            exceptionFormat = TestExceptionFormat.FULL
            showStandardStreams = true
        }
    }
}

registerKdbxBenchmark(
    name = "kdbxXmlBenchmark",
    taskDescription = "Runs streaming KDBX XML benchmarks for realistic vault sizes.",
    testPattern = "app.keemobile.kotpass.xml.benchmark.*",
)

registerKdbxBenchmark(
    name = "kdbxDecodeBenchmark",
    taskDescription = "Runs the end-to-end streaming KDBX decode benchmark.",
    testPattern = "app.keemobile.kotpass.database.benchmark.*",
)
