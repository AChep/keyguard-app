plugins {
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.detekt-custom-rules")
}

detektCustomRules {
    kmpCompilation(targetName = "android", compilationName = "main")
}

kotlin {
    compilerOptions.optIn.add("kotlin.io.encoding.ExperimentalEncodingApi")

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(project(":util:xml"))
                implementation(project(":util:io"))
                api(libs.ktor.ktor.client.core)
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.io.core)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.ktor.ktor.client.mock)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
