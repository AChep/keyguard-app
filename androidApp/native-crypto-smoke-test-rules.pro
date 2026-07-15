# The minified native-load test selects a test that does not use Mockito.
# Mockito's optional JVM-only inline mock-maker is nevertheless present in the
# shared androidTest dependency graph and has no Android Byte Buddy backend.
-dontwarn java.lang.instrument.**
-dontwarn javax.tools.**
-dontwarn net.bytebuddy.**
-dontwarn org.mockito.internal.creation.bytebuddy.inject.MockMethodDispatcher
