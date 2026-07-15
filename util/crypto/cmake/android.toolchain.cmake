# Supplies the two values the NDK toolchain cannot infer from a Cargo target triple.
if(NOT DEFINED ENV{ANDROID_NDK_ROOT})
    message(FATAL_ERROR "ANDROID_NDK_ROOT must point to the Android NDK")
endif()
if(NOT DEFINED ENV{KEYGUARD_ANDROID_ABI})
    message(FATAL_ERROR "KEYGUARD_ANDROID_ABI must name the Android ABI")
endif()
if(NOT DEFINED ENV{KEYGUARD_ANDROID_API_LEVEL})
    message(FATAL_ERROR "KEYGUARD_ANDROID_API_LEVEL must name the minimum Android API level")
endif()

set(ANDROID_ABI "$ENV{KEYGUARD_ANDROID_ABI}" CACHE STRING "" FORCE)
set(ANDROID_PLATFORM "android-$ENV{KEYGUARD_ANDROID_API_LEVEL}" CACHE STRING "" FORCE)
include("$ENV{ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake")
