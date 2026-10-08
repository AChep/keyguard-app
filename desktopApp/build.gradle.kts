import com.artemchep.keyguard.buildplugins.cargo.bundledAppResourcesFrom
import com.artemchep.keyguard.buildplugins.cargo.detectHostPlatform
import com.artemchep.keyguard.buildplugins.cargo.isLinux
import com.artemchep.keyguard.buildplugins.cargo.msixArchitecture
import com.artemchep.keyguard.buildplugins.cargo.tarballSuffix
import groovy.xml.XmlUtil
import org.apache.tools.ant.filters.ReplaceTokens
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.compose.desktop.application.tasks.AbstractNotarizationTask

plugins {
    id("keyguard.application-root")
    id("keyguard.crypto-dependency-check")
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
    id("keyguard.kotlin-multiplatform")
}

kotlin {
    // The convention picks the JDK version; the desktop app also needs JetBrains Runtime.
    jvmToolchain {
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
    jvm()
    sourceSets {
        getByName("jvmMain") {
            dependencies {
                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(libs.jetbrains.compose.runtime)
                implementation(libs.jetbrains.compose.foundation)
                implementation(libs.jetbrains.compose.material)
                implementation(libs.jetbrains.compose.material3)
                implementation(libs.jetbrains.compose.material.icons.extended)
                implementation(libs.jetbrains.compose.components.resources)
                implementation(libs.nucleusframework.composenativetray)
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlin.stdlib)
                implementation(project.dependencies.platform(libs.squareup.okhttp.bom))
                implementation(libs.squareup.okhttp)
                implementation(project(":util:crypto"))
                implementation(project(":util:instance"))
                implementation(project(":common"))
            }
        }
        getByName("jvmTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

val appId = "com.artemchep.keyguard"

val hostPlatform = detectHostPlatform()

val executableAppResourceNames = setOf(
    "keyguard-ssh-agent",
    "keyguard-gpg-agent",
    "keyguard-lib",
)

val bundledAppResources = bundledAppResourcesFrom(
    ":util:instance",
    ":util:yubikey",
    ":util:fido2",
    ":desktopSshAgent",
    ":desktopGpgAgent",
    ":desktopLibNative",
    ":util:crypto",
    ":util:io",
    ":util:zxcvbn",
)

val jdkVersion = libs.versions.jdk.get().toInt()
val jbrLauncher = extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(jdkVersion))
    vendor.set(JvmVendorSpec.JETBRAINS)
}

val macExtraPlistKeys: String
    get() = """
      <key>CFBundleLocalizations</key>
      <array>
        <string>af_ZA</string>
        <string>ca_ES</string>
        <string>de_DE</string>
        <string>es_ES</string>
        <string>ja_JP</string>
        <string>no_NO</string>
        <string>pt_PT</string>
        <string>sr_SP</string>
        <string>uk_UA</string>
        <string>zh_TW</string>
        <string>ar_SA</string>
        <string>cs_CZ</string>
        <string>el_GR</string>
        <string>fr_FR</string>
        <string>it_IT</string>
        <string>ko_KR</string>
        <string>pl_PL</string>
        <string>ro_RO</string>
        <string>sv_SE</string>
        <string>vi_VN</string>
        <string>da_DK</string>
        <string>en_US</string>
        <string>en_GB</string>
        <string>fi_FI</string>
        <string>hu_HU</string>
        <string>iw_IL</string>
        <string>nl_NL</string>
        <string>pt_BR</string>
        <string>ru_RU</string>
        <string>tr_TR</string>
        <string>zh_CN</string>
      </array>
    """

val bundledAppResourcesDir = layout.buildDirectory.dir("app-resources")

val prepareBundledAppResources = tasks.register<Sync>("prepareBundledAppResources") {
    from(bundledAppResources)
    into(bundledAppResourcesDir)
}

// Read lazily, so the configuration cache never stores the credentials.
val notarizationAppleId = providers.gradleProperty("notarization_apple_id")
    .orElse("stub_apple_id")
val notarizationPassword = providers.gradleProperty("notarization_password")
    .orElse("stub_password")
val notarizationAscProvider = providers.gradleProperty("notarization_asc_provider")
    .orElse("stub_asc_provider")

compose.desktop {
    application {
        mainClass = "com.artemchep.keyguard.MainKt"
        javaHome = jbrLauncher
            .map { it.metadata.installationPath.asFile.absolutePath }
            .get()
        nativeDistributions {
            // This tells Compose to bundle everything inside 'app-resources'
            // alongside your application in the final install image.
            appResourcesRootDir.set(bundledAppResourcesDir)

            macOS {
                iconFile.set(project.file("icon.icns"))
                entitlementsFile.set(project.file("default.entitlements"))
                infoPlist {
                    this.extraKeysRawXml = macExtraPlistKeys
                }
            }
            windows {
                iconFile.set(project.file("icon.ico"))
                // Automatically add a shortcut to the desktop:
                // https://github.com/JetBrains/compose-multiplatform/issues/1974
                shortcut = true

                // The UUID is used with `Windows Installer` to identify
                // products, components, upgrades, and other key elements of the installation process.
                // See:
                // https://wixtoolset.org/docs/v3/howtos/general/generate_guids/
                upgradeUuid = "846C6281-F349-4833-9E0E-AAE1C06006A0"
            }
            linux {
                iconFile.set(project.file("icon.png"))
            }

            // Try to go for native appearance as per:
            // https://stackoverflow.com/a/70902920/1408535
            jvmArgs(
                "-Dapple.awt.application.appearance=system",
            )
            // We want to explicitly include the jdk crypto module,
            // so if the JDK is missing that we are going to get an error
            // instead of silently building the app and failing in runtime.
            if (hostPlatform.isWindows) {
                jvmArgs("--add-modules=jdk.crypto.mscapi")
            }

            // Disabling core dumps
            jvmArgs(
                "-XX:-CreateCoredumpOnCrash",
                "-XX:-HeapDumpOnOutOfMemoryError",
            )

            includeAllModules = true
            val formats = listOfNotNull(
                TargetFormat.Dmg,
                TargetFormat.Msi,
                TargetFormat.Deb,
                // Because of this bug you can not build for macOS and
                // have the app image distribution format enabled.
                // See:
                // https://github.com/JetBrains/compose-multiplatform/issues/3814
                TargetFormat.AppImage.takeUnless { hostPlatform.isMacOs },
            ).toTypedArray()
            targetFormats(*formats)

            packageName = "Keyguard"
            packageVersion = libs.versions.appVersionName.get()

            macOS {
                bundleID = appId
                signing {
                    val certIdentity = findProperty("cert_identity") as String?
                    if (certIdentity != null) {
                        println("Signing identity ${certIdentity.take(2)}****")
                        sign.set(true)
                        identity.set(certIdentity)
                        // The certificate should be added to the
                        // keychain by this time.
                    } else {
                        println("No signing identity!")
                    }
                }
                notarization {
                    appleID.set(notarizationAppleId)
                    teamID.set(notarizationAscProvider)
                    password.set(notarizationPassword)
                }
            }
        }

        buildTypes {
            release {
                proguard {
                    isEnabled = true
                    obfuscate = true
                    optimize = true
                    configurationFiles.from(
                        project.file("../common/proguard-rules.pro"),
                        project.file("proguard-rules.pro"),
                    )
                }
            }
        }
    }
}

tasks.named { it == "prepareAppResources" }.configureEach {
    dependsOn(prepareBundledAppResources)
}

tasks.withType<AbstractNotarizationTask>().configureEach {
    // The deprecated ascProvider property of Compose's notarization settings
    // throws when the configuration cache serializes it.
    notCompatibleWithConfigurationCache("Compose notarization settings can not be serialized")
    // Task actions capture locals only: the configuration cache cannot store the script.
    val appleId = notarizationAppleId
    val password = notarizationPassword
    val ascProvider = notarizationAscProvider
    doFirst {
        println("Notarization Apple Id ${appleId.get().take(2)}****")
        println("Notarization Password ${password.get().take(2)}****")
        println("Notarization ASC Provider ${ascProvider.get().take(2)}****")
    }
}

if (!hostPlatform.isWindows) {
    // Task actions capture locals only: the configuration cache cannot store the script.
    val executableNames = executableAppResourceNames
    val appResourcesPath = if (hostPlatform.isMacOs) ".app/Contents/app/resources" else "/lib/app/resources"
    tasks.withType<AbstractJPackageTask>().configureEach {
        if (targetFormat != TargetFormat.AppImage) {
            return@configureEach
        }

        val appResourcesDirectory = destinationDir.zip(packageName) { dir, name -> dir.dir(name + appResourcesPath) }

        // Compose's app-resource copy does not preserve POSIX executable bits.
        // Repair the app image before verification or downstream packaging.
        outputs.upToDateWhen {
            executableNames.all { name ->
                appResourcesDirectory.get().asFile.resolve(name).canExecute()
            }
        }
        doLast {
            executableNames.forEach { name ->
                val executable = appResourcesDirectory.get().asFile.resolve(name)
                check(executable.isFile) {
                    "Bundled executable is missing: $executable"
                }
                check(executable.setExecutable(true, false) && executable.canExecute()) {
                    "Could not mark bundled resource as executable: $executable"
                }
            }
        }
    }
}

fun Tar.installPackageDistributable(
    dependency: String,
) {
    val appVersion = libs.versions.appVersionName.get()
    val executableNames = executableAppResourceNames

    from(tasks.named(dependency)) {
        // Keep the launcher and helper binaries executable inside the tarball even if
        // their source mode was normalized by an upstream packaging step.
        eachFile {
            if (
                name == "Keyguard" ||
                name == "jspawnhelper" || // https://github.com/AChep/keyguard-app/issues/640#issuecomment-4111835953
                name in executableNames
            ) {
                permissions { unix("755") }
            }
        }
    }

    // Pack additional platform-specific files. For example for
    // Linux we want to include the Flatpak files.
    if (hostPlatform.isLinux) {
        val flatpakSources = project.file("flatpak")
        from(flatpakSources) {
            include("com.artemchep.keyguard.desktop")
            into("Keyguard/share/applications")
        }
        from(flatpakSources) {
            include("com.artemchep.keyguard.metainfo.xml")
            into("Keyguard/share/metainfo")
        }
        // polkit policy of the system authentication unlock. polkitd
        // only reads the host's /usr/share, the copy here is what the
        // documented install command reads.
        from(layout.settingsDirectory.dir("desktopLibNative/src/src/biometrics/linux")) {
            include("com.artemchep.keyguard.policy")
            into("Keyguard/share/polkit-1/actions")
        }
        from(flatpakSources) {
            include("icon.svg")
            // Rename happens on the fly during the copy
            rename { "com.artemchep.keyguard.svg" }
            into("Keyguard/share/icons/hicolor/scalable/apps")
        }
    }

    archiveBaseName = "Keyguard"
    archiveClassifier = "$appVersion-${hostPlatform.tarballSuffix}"
    compression = Compression.GZIP
    archiveExtension = "tar.gz"
}

tasks.register<Tar>("packageDistributable") {
    installPackageDistributable("createDistributable")
}

tasks.register<Tar>("packageReleaseDistributable") {
    installPackageDistributable("createReleaseDistributable")
}

// MSIX packaging (Windows only). jpackage can not produce MSIX, so we pack
// the app image together with the manifest and the tile assets from the
// 'msix' directory using the Windows SDK tools. See msix/README.md.
if (hostPlatform.isWindows) {
    val appVersion = libs.versions.appVersionName.get()
    val msixArchitecture = hostPlatform.msixArchitecture

    fun msixProperty(name: String): String? = (findProperty(name) as String?)
        ?.takeIf { it.isNotBlank() }

    // Task actions call windowsSdkTool, so it must not capture the script:
    // the configuration cache can not store it.
    val msixSdkBinDir = msixProperty("msix_sdk_bin_dir")

    // Finds a Windows SDK tool, preferring the highest installed SDK version.
    fun windowsSdkTool(name: String): File {
        msixSdkBinDir?.let { return File(it, name) }
        // The ARM64 SDK tools are not present in every SDK installation. The
        // x64 tools can still pack and sign ARM64 packages under emulation.
        val toolArchitectures = listOf(msixArchitecture, "x64").distinct()
        val tool = listOfNotNull(System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"))
            .map { File(it, "Windows Kits/10/bin") }
            .flatMap { it.listFiles()?.toList().orEmpty() }
            .filter { it.name.startsWith("10.") }
            .sortedByDescending { dir ->
                dir.name.split('.')
                    .fold(0L) { acc, part -> acc * 100000 + (part.toLongOrNull() ?: 0L) }
            }
            .asSequence()
            .flatMap { dir ->
                toolArchitectures.asSequence().map { architecture ->
                    dir.resolve("$architecture/$name")
                }
            }
            .firstOrNull { it.isFile }
        return tool
            ?: error(
                "$name was not found for $msixArchitecture. " +
                    "Install the Windows 10/11 SDK or set -Pmsix_sdk_bin_dir=<dir>.",
            )
    }

    data class MsixFlavor(
        val id: String,
        val fileName: String,
        val identityName: String?,
        val publisher: String?,
    ) {
        val isConfigured get() = identityName != null && publisher != null
    }

    val msixVersion = msixProperty("msix_version") ?: "$appVersion.0"
    val msixPublisherDisplayName = msixProperty("msix_publisher_display_name") ?: "Artem Chepurnyi"
    val msixFlavors = listOf(
        // Identity values come from the Partner Center, the Store
        // signs the package itself.
        MsixFlavor(
            id = "store",
            fileName = "Keyguard-$appVersion-$msixArchitecture-store.msix",
            identityName = msixProperty("msix_store_identity_name"),
            publisher = msixProperty("msix_store_publisher"),
        ),
        // Publisher must match the subject of the signing certificate.
        MsixFlavor(
            id = "sideload",
            fileName = "Keyguard-$appVersion-$msixArchitecture.msix",
            identityName = msixProperty("msix_sideload_identity_name") ?: "ArtemChepurnyi.Keyguard",
            publisher = msixProperty("msix_sideload_publisher") ?: "CN=Artem Chepurnyi",
        ),
    )

    fun registerMsixTasks(
        buildType: String,
        binariesDirName: String,
    ) {
        val binariesDir = layout.buildDirectory.dir("compose/binaries/$binariesDirName")
        val appImageDir = binariesDir.map { it.dir("app/Keyguard") }
        val outputDir = binariesDir.map { it.dir("msix") }

        val packageTasks = msixFlavors.map { flavor ->
            val flavorName = flavor.id.replaceFirstChar { it.uppercase() }
            val isConfigured = flavor.isConfigured
            val stagingDir = binariesDir.map { it.dir("msix-staging/${flavor.id}") }
            val prepareTask = tasks.register<Sync>("prepare${buildType}Msix$flavorName") {
                val manifestTokens = mapOf(
                    "IDENTITY_NAME" to flavor.identityName.orEmpty(),
                    "PUBLISHER" to flavor.publisher.orEmpty(),
                    "PUBLISHER_DISPLAY_NAME" to msixPublisherDisplayName,
                    "PROCESSOR_ARCHITECTURE" to msixArchitecture,
                    "VERSION" to msixVersion,
                ).mapValues { (_, value) -> XmlUtil.escapeXml(value) }
                inputs.property("manifestTokens", manifestTokens)
                dependsOn("create${buildType}Distributable")
                onlyIf { isConfigured }
                filteringCharset = "UTF-8"
                from(appImageDir)
                from(project.file("msix/Assets")) {
                    into("Assets")
                }
                from(project.file("msix/AppxManifest.xml")) {
                    filter<ReplaceTokens>(
                        "tokens" to manifestTokens,
                    )
                }
                into(stagingDir)
            }
            tasks.register<Exec>("package${buildType}Msix$flavorName") {
                group = "compose desktop"
                description = "Packs the ${flavor.id} MSIX package."
                dependsOn(prepareTask)
                onlyIf { isConfigured }
                val output = outputDir.map { it.file(flavor.fileName) }
                inputs.dir(stagingDir)
                outputs.file(output)
                doFirst {
                    val file = output.get().asFile
                    file.parentFile.mkdirs()
                    file.delete()
                    commandLine(
                        windowsSdkTool("makeappx.exe").absolutePath,
                        "pack", "/o",
                        "/d", stagingDir.get().asFile.absolutePath,
                        "/p", file.absolutePath,
                    )
                }
            }
        }

        val sideloadFileName = msixFlavors.first { it.id == "sideload" }.fileName
        val pfxPassword = providers.gradleProperty("msix_sideload_pfx_password")
        val signTask = tasks.register<Exec>("sign${buildType}Msix") {
            group = "compose desktop"
            description = "Signs the sideload MSIX package, if a certificate is configured."
            dependsOn(packageTasks)
            val pfxPath = msixProperty("msix_sideload_pfx_path")
            onlyIf {
                if (pfxPath == null) {
                    println("No MSIX signing certificate, the sideload package is left unsigned!")
                }
                pfxPath != null
            }
            doFirst {
                val args = mutableListOf(
                    windowsSdkTool("signtool.exe").absolutePath,
                    "sign",
                    "/fd", "SHA256",
                    "/td", "SHA256",
                    "/tr", "http://timestamp.digicert.com",
                    "/f", pfxPath!!,
                )
                pfxPassword.orNull
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { args += listOf("/p", it) }
                args += outputDir.get().file(sideloadFileName).asFile.absolutePath
                commandLine(args)
            }
        }

        tasks.register("package${buildType}Msix") {
            group = "compose desktop"
            description = "Packs the store and sideload MSIX packages."
            dependsOn(packageTasks)
            dependsOn(signTask)
        }
    }

    registerMsixTasks(buildType = "", binariesDirName = "main")
    registerMsixTasks(buildType = "Release", binariesDirName = "main-release")
}
