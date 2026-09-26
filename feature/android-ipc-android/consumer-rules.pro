# OpenKeychain-compatible IPC contracts cross process boundaries. Preserve the
# AIDL stubs and Parcelable class names when a consuming Android app is shrunk.
-keep class org.openintents.openpgp.** { *; }
-keep interface org.openintents.openpgp.** { *; }
-keep class org.openintents.ssh.authentication.** { *; }
-keep interface org.openintents.ssh.authentication.** { *; }
