import com.artemchep.keyguard.buildplugins.cargo.CargoBuildTask
import com.artemchep.keyguard.buildplugins.cargo.RustMultiplatformLibraryExtension
import com.artemchep.keyguard.buildplugins.cargo.binaryName
import com.artemchep.keyguard.buildplugins.cargo.detectHostPlatform
import com.artemchep.keyguard.buildplugins.cargo.dynamicLibraryName
import org.gradle.api.tasks.testing.Test

plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform")
    id("keyguard.rust-multiplatform-library")
}

kotlin {
    jvm("desktop")
    macosArm64()
}

// Windows instance coordination reuses IO's handle-relative filesystem primitives.
val ioRustSources = rootProject.fileTree("util/io/rust") {
    exclude("target/**", "**/target/**")
}
extensions.configure<RustMultiplatformLibraryExtension> {
    extraSourceInputs.from(ioRustSources)
}

val hostPlatform = detectHostPlatform()
val desktopLibrary = layout.buildDirectory.file(
    "cargo-target/${hostPlatform.rustTarget}/release/" +
        hostPlatform.dynamicLibraryName("keyguard_instance_jni"),
)
val nativeFixture = tasks.register<CargoBuildTask>("cargoBuildNativeInstanceFixture") {
    dependsOn("verifyNativeInstanceDesktopRustTarget")
    // Share compatible Cargo artifacts, serializing writers when both tasks are requested.
    mustRunAfter("cargoBuildNativeInstanceDesktop")
    sourceDir.set(layout.projectDirectory.dir("rust"))
    sourceFiles.from(fileTree("rust") { exclude("target/**", "**/target/**") })
    sourceFiles.from(ioRustSources)
    cargoTargetDir.set(layout.buildDirectory.dir("cargo-target"))
    rustTarget.set(hostPlatform.rustTarget)
    cargoPackage.set("keyguard-instance-core")
    cargoArguments.addAll("--locked", "--bin", "instance-fixture")
    outputBinary.set(
        layout.buildDirectory.file(
            "cargo-target/${hostPlatform.rustTarget}/release/" +
                hostPlatform.binaryName("instance-fixture"),
        ),
    )
}
tasks.named { it == "compileNativeInstanceDesktop" }.configureEach {
    // Package the JNI library after all scheduled writers to the shared Cargo directory finish.
    mustRunAfter(nativeFixture)
}
tasks.withType<Test>().configureEach {
    dependsOn("compileNativeInstanceDesktop")
    dependsOn(nativeFixture)
    inputs.file(desktopLibrary).withPropertyName("nativeInstanceDesktopLibrary")
    inputs.file(nativeFixture.flatMap { it.outputBinary }).withPropertyName("nativeInstanceFixture")
    systemProperty("keyguard.nativeInstance.libraryPath", desktopLibrary.get().asFile.absolutePath)
    systemProperty("keyguard.nativeInstance.fixturePath", nativeFixture.get().outputBinary.get().asFile.absolutePath)
    jvmArgs("-Xcheck:jni")
}
