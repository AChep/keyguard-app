# JNA's native dispatcher resolves private core methods and fields by JNI name.
# Keep only the top-level core package (not com.sun.jna.platform.**) intact,
# while preserving application-defined native interfaces and structures below.
-keep,allowobfuscation class com.sun.jna.* { *; }
-keepclassmembers,allowoptimization interface * extends com.sun.jna.Library { <methods>; }
-keepclassmembers,allowoptimization interface * extends com.sun.jna.Callback { <methods>; }
-keepclassmembers,allowoptimization class * extends com.sun.jna.Structure { <fields>; }

# D-Bus discovers its native transport with ServiceLoader and reflects over
# wire interfaces, signals, errors, containers, and serializable values.
-keepattributes Signature
-keep,allowoptimization class org.freedesktop.dbus.transport.jre.NativeTransportProvider {
    public <init>();
}
-keep,allowoptimization class org.freedesktop.dbus.errors.** {
    public <init>(java.lang.String);
}
-keep,allowoptimization interface * extends org.freedesktop.dbus.interfaces.DBusInterface {
    <methods>;
}
-keep,allowoptimization class * extends org.freedesktop.dbus.messages.DBusSignal {
    <init>(...);
}
-keepclassmembers,allowoptimization class * extends org.freedesktop.dbus.Container {
    @org.freedesktop.dbus.annotations.Position <fields>;
}
-keepclassmembers,allowoptimization class * implements org.freedesktop.dbus.interfaces.DBusSerializable {
    public java.lang.Object[] serialize();
    public void deserialize(...);
}

# ImageIO and SQLite providers are loaded from META-INF/services. SQLite's
# native library also resolves the classes below by hard-coded JNI names, and
# SQLiteConfigFactory enumerates its implementations through Class reflection.
-keep,allowoptimization class com.github.jaiimageio.impl.**.*Spi {
    public <init>();
}
-keep,allowoptimization class org.sqlite.JDBC {
    public <init>();
}
-keep enum org.sqlite.SQLiteConfigFactory { *; }
-keep class org.sqlite.core.NativeDB { *; }
-keep class org.sqlite.Function { *; }
-keep class org.sqlite.Function$** { *; }
-keep class org.sqlite.Collation { *; }
-keep class org.sqlite.BusyHandler { *; }
-keep class org.sqlite.ProgressHandler { *; }
-keep class org.sqlite.core.DB$ProgressObserver { *; }

# LinkExtractor uses EnumSet.allOf(), so LinkType must remain an enum after
# optimization. No other AutoLink classes use reflection.
-keep enum org.nibor.autolink.LinkType { *; }

# Kaverit supplies these rules to Android consumers, but its JVM artifacts do
# not include them. Kodein reflects over these type-token subclasses.
-keep,allowobfuscation,allowoptimization class org.kodein.type.TypeReference
-keep,allowobfuscation,allowoptimization class org.kodein.type.JVMAbstractTypeToken$Companion$WrappingTest
-keep,allowobfuscation,allowoptimization class * extends org.kodein.type.TypeReference
-keep,allowobfuscation,allowoptimization class * extends org.kodein.type.JVMAbstractTypeToken$Companion$WrappingTest

# Preserve Kotlin's sealed-subclass metadata for the persisted vault filters.
# ProGuard 7.8 otherwise rewrites the permitted-subclasses relationship into
# an invalid hierarchy when the vault screen initializes.
-keep class com.artemchep.keyguard.common.model.DFilter { *; }
-keep class com.artemchep.keyguard.common.model.DFilter$** { *; }

# ProGuard 7.8 can produce invalid return bytecode when optimizing Compose's
# generated Desktop paragraph factory. Keep shrinking enabled for the class,
# but preserve its method bodies.
-keep,allowshrinking,allowobfuscation class androidx.compose.ui.text.ParagraphKt__ActualParagraph_skikoKt { *; }

# ProGuard 7.8 can also generate an invalid covariant return bridge for Okio's
# Kotlin buffer(Source) factory. Allow unused classes to be removed, but keep
# the remaining method bodies intact.
-keep,allowshrinking,allowobfuscation class okio.Okio__OkioKt { *; }

# The SSH and GPG helper processes share this desktop IPC transport. ProGuard
# 7.8 changes its authentication/session behavior when optimizing the package.
# This protects 135 shared transport classes instead of all 500 agent classes.
-keep,allowshrinking,allowobfuscation class com.artemchep.keyguard.common.service.agent.** { *; }
-keep,allowshrinking,allowobfuscation class com.artemchep.keyguard.common.service.sshagent.SshAgentPacketSessionKt { *; }
-keep,allowshrinking,allowobfuscation class com.artemchep.keyguard.common.service.sshagent.SshAgentPacketSessionKt$* { *; }
-keep,allowshrinking,allowobfuscation class com.artemchep.keyguard.common.service.sshagent.SshAgentProtoCodec { *; }
-keep,allowshrinking,allowobfuscation class com.artemchep.keyguard.common.service.gpgagent.GpgAgentPacketSessionKt { *; }
-keep,allowshrinking,allowobfuscation class com.artemchep.keyguard.common.service.gpgagent.GpgAgentPacketSessionKt$* { *; }
-keep,allowshrinking,allowobfuscation class com.artemchep.keyguard.common.service.gpgagent.GpgAgentProtoCodec { *; }

# OkHttp supports optional TLS providers and GraalVM native-image integrations
# that are not part of the Desktop application runtime.
-dontwarn org.graalvm.nativeimage.**
-dontwarn com.oracle.svm.core.annotate.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Kodein's inline instance lookup can leave a reference to a compiler-generated
# class that is not emitted into the published artifact.
-dontwarn org.kodein.di.compose.RetrievingKt$rememberNamedInstance$1$1$invoke$$inlined$instance-**

# Skiko/Skia API references changed between Compose versions.
-dontwarn com.kdroid.composetray.**
-dontwarn io.github.vinceglb.filekit.**
