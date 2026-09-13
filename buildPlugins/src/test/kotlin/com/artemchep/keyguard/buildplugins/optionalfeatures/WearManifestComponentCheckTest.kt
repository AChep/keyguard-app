package com.artemchep.keyguard.buildplugins.optionalfeatures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WearManifestComponentCheckTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun findsFullyQualifiedAndRelativeComponentNames() {
        val manifest = manifest(
            """
            <activity android:name="com.artemchep.keyguard.android.ipc.AndroidIpcApprovalActivity" />
            <service android:name=".android.ipc.SshAuthenticationService" />
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "activity com.artemchep.keyguard.android.ipc.AndroidIpcApprovalActivity",
                "service com.artemchep.keyguard.android.ipc.SshAuthenticationService",
            ),
            findForbiddenWearManifestComponents(manifest),
        )
    }

    @Test
    fun resolvesBareComponentNamesAgainstTheManifestPackage() {
        val manifest = manifest(
            """<service android:name="OpenPgpService" />""",
            packageName = "com.artemchep.keyguard.android.ipc",
        )

        assertEquals(
            listOf("service com.artemchep.keyguard.android.ipc.OpenPgpService"),
            findForbiddenWearManifestComponents(manifest),
        )
    }

    @Test
    fun rejectsFutureProvidersAndReceiversEvenWhenDisabledOrUnexported() {
        val manifest = manifest(
            """
            <provider android:name=".android.ipc.future.KeyProvider" android:enabled="false" />
            <receiver android:name=".android.ipc.KeyReceiver" android:exported="false" />
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "provider com.artemchep.keyguard.android.ipc.future.KeyProvider",
                "receiver com.artemchep.keyguard.android.ipc.KeyReceiver",
            ),
            findForbiddenWearManifestComponents(manifest),
        )
    }

    @Test
    fun checksAliasTargets() {
        val manifest = manifest(
            """
            <activity-alias
                android:name=".wear.ApprovalAlias"
                android:targetActivity=".android.ipc.AndroidIpcApprovalActivity" />
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "activity-alias .wear.ApprovalAlias -> com.artemchep.keyguard.android.ipc.AndroidIpcApprovalActivity",
            ),
            findForbiddenWearManifestComponents(manifest),
        )
    }

    @Test
    fun allowsUnrelatedNamesakesAliasesMetadataAndQueries() {
        val manifest = manifest(
            """
            <activity android:name=".wear.WearActivity" />
            <service android:name="com.example.wear.OpenPgpService" />
            <service android:name="OpenPgpService" />
            <service android:name=".android.ipcother.OpenPgpService" />
            <activity-alias
                android:name=".android.ipc.AndroidIpcApprovalActivity"
                android:targetActivity=".wear.WearActivity" />
            <meta-data android:name="com.artemchep.keyguard.android.ipc.OpenPgpService" android:value="disabled" />
            """.trimIndent(),
        )
        manifest.writeText(
            manifest.readText().replace(
                "</manifest>",
                """<queries><provider android:authorities="com.example.provider" /></queries></manifest>""",
            ),
        )

        assertEquals(emptyList<String>(), findForbiddenWearManifestComponents(manifest))
    }

    @Test
    fun rejectsShorthandWithoutAManifestPackage() {
        listOf(".android.ipc.OpenPgpService", "OpenPgpService").forEach { name ->
            val manifest = manifest("""<service android:name="$name" />""", packageName = null)

            val error = assertThrows(IllegalStateException::class.java) {
                findForbiddenWearManifestComponents(manifest)
            }
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("without a merged manifest package"))
        }
    }

    private fun manifest(components: String, packageName: String? = "com.artemchep.keyguard") =
        temporaryFolder.newFile().apply {
            val packageAttribute = packageName?.let { "package=\"$it\"" }.orEmpty()
            writeText(
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android" $packageAttribute>
                    <application>$components</application>
                </manifest>
                """.trimIndent(),
            )
        }
}
