plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
}

group = "com.artemchep.keyguard.buildplugins"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get().toInt()))
}

dependencies {
    implementation("com.android.tools.build:gradle:${libs.versions.androidPlugin.get()}")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
}

gradlePlugin {
    plugins {
        register("resourcesCommon") {
            id = "keyguard.resources-common"
            implementationClass = "com.artemchep.keyguard.buildplugins.resources.ResourcesCommonPlugin"
        }
        register("cargoCommon") {
            id = "keyguard.cargo-common"
            implementationClass = "com.artemchep.keyguard.buildplugins.cargo.CargoCommonPlugin"
        }
        register("androidSshAgent") {
            id = "keyguard.android-ssh-agent"
            implementationClass = "com.artemchep.keyguard.buildplugins.androidssh.AndroidSshAgentPlugin"
        }
    }
}
