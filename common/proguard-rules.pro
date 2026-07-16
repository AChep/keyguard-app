# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

##
## java rx
##

# https://github.com/ReactiveX/RxJava#r8-and-proguard-settings
-dontwarn java.util.concurrent.Flow*

##
## signalr
##

-keep class com.microsoft.signalr.** { *; }
-keep interface com.microsoft.signalr.** { *; }

##
## messagepack
##

-keep class org.msgpack.core.buffer.** { *; }

##
## ksoup
##

# Ksoup HTML Parser - Keep interface methods for Kotlin delegation pattern
# The Builder pattern uses `object : Interface by delegate` which requires
# all interface methods to be preserved.

-keep,allowshrinking class * implements com.mohamedrejeb.ksoup.html.parser.KsoupHtmlHandler {
    <methods>;
}

##
## dont warn
##

-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn java.sql.JDBCType
#-dontwarn okhttp3.internal.platform.**
#-dontwarn org.conscrypt.**
#-dontwarn org.openjsse.**
