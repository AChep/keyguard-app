plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.compose-free")
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(project(":feature:cli"))
            }
        }
    }
}
