import com.artemchep.keyguard.buildplugins.testing.registerJvmBenchmark
import com.artemchep.keyguard.buildplugins.kotlin.sharedAppleMain
import com.artemchep.keyguard.buildplugins.kotlin.sharedJvmMain
import com.artemchep.keyguard.buildplugins.kotlin.sharedJvmTest
import org.gradle.api.tasks.testing.Test

plugins {
    id("keyguard.crypto-dependency-check")
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.native-crypto-consumer")
    id("keyguard.detekt-custom-rules")
}

detektCustomRules {
    kmpCompilation(targetName = "android", compilationName = "main")
    // Host-only tests do not become part of an Android artifact.
    excludeSourcePathFromCoverage("src/jvmCommonTest")
}

kotlin {
    android {
        namespace = "com.artemchep.keyguard.util.kdbx"
    }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(libs.kotlinx.io.core)
                api(libs.squareup.okio)
                implementation(libs.xmlutil.core)
                implementation(project(":util:crypto"))
                implementation(project(":util:foundation"))
            }
        }

        sharedJvmTest().dependencies {
            implementation(libs.bouncycastle.bcprov)
        }
        sharedJvmMain(name = "jvmCommonMain")
        sharedAppleMain(includeAppleMain = false)

        all {
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
        }
    }
}

val desktopTestTask = tasks.named<Test>("desktopTest")

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
    registerJvmBenchmark(name, taskDescription, testPattern, includeSkipped = false, useEnglishLocale = false) {
        kdbxJfrRecording?.let { recording ->
            jvmArgs(
                "-XX:StartFlightRecording=" +
                    "filename=$recording,settings=profile,dumponexit=true",
            )
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
