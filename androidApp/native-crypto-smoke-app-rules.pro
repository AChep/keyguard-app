# AndroidJUnitRunner loads this class from the minified target APK because the
# tracing dependency is shared between the app and its instrumentation APK.
-keep class androidx.tracing.Trace { *; }
