import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
    }
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(libs.jetbrains.compose.runtime)
                implementation(libs.jetbrains.compose.foundation)
                implementation(libs.jetbrains.compose.material)
                implementation(libs.jetbrains.compose.material3)
                implementation(libs.jetbrains.compose.material.icons.extended)
                implementation(libs.jetbrains.compose.components.resources)
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlin.stdlib)
                implementation(libs.bouncycastle.bcprov)
                implementation(libs.bouncycastle.bctls)
                implementation(project(":common"))
            }
        }
    }
}

val appId = "com.artemchep.keyguard"

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

compose.desktop {
    application {
        mainClass = "com.artemchep.keyguard.MainKt"
        nativeDistributions {
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
            includeAllModules = true
            val formats = listOfNotNull(
                TargetFormat.Dmg,
                TargetFormat.Msi,
                TargetFormat.Deb,
                // Because of this bug you can not build for macOS and
                // have the app image distribution format enabled.
                // See:
                // https://github.com/JetBrains/compose-multiplatform/issues/3814
                TargetFormat.AppImage.takeUnless { Os.isFamily(Os.FAMILY_MAC) },
            ).toTypedArray()
            targetFormats(*formats)

            packageName = "Keyguard"
            packageVersion = libs.versions.appVersionName.get()

            macOS {
                bundleID = "com.artemchep.keyguard"
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
                    val notarizationAppleId = findProperty("notarization_apple_id") as String?
                        ?: "stub_apple_id"
                    val notarizationPassword = findProperty("notarization_password") as String?
                        ?: "stub_password"
                    val notarizationAscProvider =
                        findProperty("notarization_asc_provider") as String?
                            ?: "stub_asc_provider"
                    println("Notarization Apple Id ${notarizationAppleId.take(2)}****")
                    println("Notarization Password ${notarizationPassword.take(2)}****")
                    println("Notarization ASC Provider ${notarizationAscProvider.take(2)}****")
                    appleID.set(notarizationAppleId)
                    teamID.set(notarizationAscProvider)
                    password.set(notarizationPassword)
                }
            }
        }

        buildTypes {
            release {
                proguard {
                    // Enabling the proguard would require us to grab the .jar of
                    // the BouncyCastle library and strip out the signature due to this error:
                    //
                    // Exception in thread "main" java.lang.SecurityException:
                    // SHA-256 digest error for org/bouncycastle/jce/provider/BouncyCastleProvider.class
                    isEnabled = false
                }
            }
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

fun Tar.installPackageDistributable(
    dependency: String,
) {
    val appVersion = libs.versions.appVersionName.get()
    val osName = System.getProperty("os.name")
        .lowercase()
        .replace(" ", "")
    val osArch = when (val prop = System.getProperty("os.arch")) {
        "amd64" -> "x86_64"
        else -> prop
    }

    from(tasks.named(dependency))

    // Pack additional platform-specific files. For example for
    // Linux we want to include the Flatpak files.
    when (osName) {
        "linux" -> {
            val flatpakSources = project.file("flatpak")
            from(flatpakSources) {
                include("com.artemchep.keyguard.desktop")
                into("Keyguard/share/applications")
            }
            from(flatpakSources) {
                include("com.artemchep.keyguard.metainfo.xml")
                into("Keyguard/share/metainfo")
            }
            from(flatpakSources) {
                include("icon.svg")
                // Rename happens on the fly during the copy
                rename { "com.artemchep.keyguard.svg" }
                into("Keyguard/share/icons/hicolor/scalable/apps")
            }
        }
        else -> {
            // Do nothing
        }
    }

    archiveBaseName = "Keyguard"
    archiveClassifier = "$appVersion-$osName-$osArch"
    compression = Compression.GZIP
    archiveExtension = "tar.gz"
}

tasks.register<Tar>("packageDistributable") {
    installPackageDistributable("createDistributable")
}

tasks.register<Tar>("packageReleaseDistributable") {
    installPackageDistributable("createReleaseDistributable")
}
