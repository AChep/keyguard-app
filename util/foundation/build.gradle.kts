import com.artemchep.keyguard.buildplugins.kotlin.sharedAppleMain
import com.artemchep.keyguard.buildplugins.kotlin.sharedJvmMain
import com.artemchep.keyguard.buildplugins.kotlin.sharedJvmTest

plugins {
    id("keyguard.crypto-dependency-check")
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.native-crypto-consumer")
}

kotlin {
    android {
        namespace = "com.artemchep.keyguard.util.foundation"
    }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(project(":util:crypto"))
                api(libs.kotlinx.io.core)
            }
        }

        sharedJvmTest().dependencies {
            implementation(libs.bouncycastle.bcprov)
        }
        sharedJvmMain(name = "jvmCommonMain")
        sharedAppleMain()

        all {
            languageSettings.optIn("kotlin.ExperimentalStdlibApi")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
        }
    }
}
