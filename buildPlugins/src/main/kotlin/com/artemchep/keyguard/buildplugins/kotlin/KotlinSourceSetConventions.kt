package com.artemchep.keyguard.buildplugins.kotlin

import org.gradle.api.NamedDomainObjectContainer
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

/** Shares JVM implementations without changing the existing source directory name. */
fun NamedDomainObjectContainer<KotlinSourceSet>.sharedJvmMain(
    name: String = "jvmMain",
): KotlinSourceSet {
    val shared = create(name) {
        dependsOn(getByName("commonMain"))
    }
    getByName("androidMain").dependsOn(shared)
    getByName("desktopMain").dependsOn(shared)
    return shared
}

/** Shares JVM-only test sources and dependencies between Android host tests and desktop tests. */
fun NamedDomainObjectContainer<KotlinSourceSet>.sharedJvmTest(
    name: String = "jvmCommonTest",
): KotlinSourceSet {
    val shared = create(name) {
        dependsOn(getByName("commonTest"))
    }
    getByName("androidHostTest").dependsOn(shared)
    getByName("desktopTest").dependsOn(shared)
    return shared
}

/** Keeps the optional Apple parent separate from the iOS and macOS source sets. */
fun NamedDomainObjectContainer<KotlinSourceSet>.sharedAppleMain(
    includeAppleMain: Boolean = true,
) {
    val commonMain = getByName("commonMain")
    val parent = if (includeAppleMain) {
        create("appleMain") { dependsOn(commonMain) }
    } else {
        commonMain
    }
    val iosMain = create("iosMain") { dependsOn(parent) }
    getByName("iosArm64Main").dependsOn(iosMain)
    getByName("iosSimulatorArm64Main").dependsOn(iosMain)
    val macosMain = create("macosMain") { dependsOn(parent) }
    getByName("macosArm64Main").dependsOn(macosMain)
}

/**
 * Shares the client of a desktop Rust library between the desktop JVM and Apple targets, which
 * both call the native code through `:util:ffi`. Call it after [sharedAppleMain].
 */
fun NamedDomainObjectContainer<KotlinSourceSet>.sharedNativeClient() {
    val main = create("nativeClientMain") {
        dependsOn(getByName("commonMain"))
        dependencies { implementation(project(":util:ffi")) }
    }
    getByName("desktopMain").dependsOn(main)
    getByName("appleMain").dependsOn(main)
    val test = create("nativeClientTest") { dependsOn(getByName("commonTest")) }
    getByName("desktopTest").dependsOn(test)
    getByName("macosArm64Test").dependsOn(test)
}

/** Test counterpart of [sharedAppleMain]: `appleTest` is shared by the iOS and macOS tests. */
fun NamedDomainObjectContainer<KotlinSourceSet>.sharedAppleTest(
    parent: KotlinSourceSet = getByName("commonTest"),
) {
    val appleTest = create("appleTest") { dependsOn(parent) }
    val iosTest = create("iosTest") { dependsOn(appleTest) }
    getByName("iosArm64Test").dependsOn(iosTest)
    getByName("iosSimulatorArm64Test").dependsOn(iosTest)
    getByName("macosArm64Test").dependsOn(appleTest)
}

/** Adds the shared iOS test directory only to libraries that already use it. */
fun NamedDomainObjectContainer<KotlinSourceSet>.sharedIosTest() {
    val commonTest = getByName("commonTest")
    val iosTest = create("iosTest") { dependsOn(commonTest) }
    getByName("iosArm64Test").dependsOn(iosTest)
    getByName("iosSimulatorArm64Test").dependsOn(iosTest)
}
