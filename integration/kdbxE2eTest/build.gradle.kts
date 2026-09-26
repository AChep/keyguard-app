import com.artemchep.keyguard.buildplugins.testing.E2eToolchain
import com.artemchep.keyguard.buildplugins.testing.VerifyE2eEnvironmentTask
import com.artemchep.keyguard.buildplugins.testing.pythonSetupMessage
import org.gradle.api.tasks.testing.Test

plugins {
    id("keyguard.quality")
    id("keyguard.jvm-e2e")
    id("keyguard.native-crypto-consumer")
}

dependencies {
    "kdbxE2eTestImplementation"(project(":util:kdbx"))
    "kdbxE2eTestImplementation"(libs.kotlinx.serialization.json)
}

val kdbxPython = providers.gradleProperty("kdbxE2ePython").orElse("python3")
val kdbxDriver = layout.projectDirectory.file("python/kdbx_e2e.py")
val requirements = layout.projectDirectory.file("requirements.txt")
val seedDirectory = rootProject.layout.projectDirectory.dir("util/kdbx/src/jvmCommonTest/resources")
val artifactsDirectory = layout.buildDirectory.dir("kdbxE2eTest/artifacts")

val verifyE2eEnvironment = tasks.register<VerifyE2eEnvironmentTask>("verifyE2eEnvironment") {
    toolchain.set(E2eToolchain.PYKEEPASS)
    pythonExecutable.set(kdbxPython)
    pythonDriver.set(kdbxDriver)
    val requirementsFile = requirements.asFile
    pythonSetupHint.set(kdbxPython.map { python -> pythonSetupMessage(python, requirementsFile) })
}

tasks.named<Test>("kdbxE2eTest") {
    description = "Runs KDBX interoperability tests against pykeepass."
    dependsOn(verifyE2eEnvironment)

    systemProperty("keyguard.repoRoot", rootDir.absolutePath)
    systemProperty("keyguard.kdbxE2e.python", kdbxPython.get())
    systemProperty("keyguard.kdbxE2e.driver", kdbxDriver.asFile.absolutePath)
    systemProperty("keyguard.kdbxE2e.seedDir", seedDirectory.asFile.absolutePath)
    systemProperty("keyguard.kdbxE2e.artifactsDir", artifactsDirectory.get().asFile.absolutePath)
}
