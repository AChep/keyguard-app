#!/bin/sh
# Builds the jpackage Linux app launcher from src/ with the host C compiler.
#
# Usage: build.sh <jdk-include-dir> <output-file>
#
# <jdk-include-dir> is the include/ directory of the JDK that runs jpackage,
# for jni.h and linux/jni_md.h.
#
# The flags are close to how the JDK builds it: CFLAGS_JDKEXE,
# OPTIMIZATION LOW and -ldl, see make/modules/jdk.jpackage/Lib.gmk.
set -eu

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <jdk-include-dir> <output-file>" >&2
    exit 2
fi
jdk_include=$1
output=$2

cc=${CC:-cc}
if ! command -v "$cc" > /dev/null 2>&1; then
    echo "C compiler '$cc' not found. It builds the Linux app launcher;" \
        "install one, e.g. \`sudo apt-get install build-essential\`." >&2
    exit 1
fi

src=$(cd "$(dirname "$0")/src/jdk.jpackage" && pwd)
linux_dir=$src/linux/native/applauncher
share_dir=$src/share/native/applauncher

mkdir -p "$(dirname "$output")"
"$cc" \
    -O2 -std=c11 -fPIE -fstack-protector-strong -fno-strict-aliasing \
    -D_GNU_SOURCE -D_REENTRANT -DLINUX -DNDEBUG -Wall \
    -I"$linux_dir" \
    -I"$share_dir" \
    -I"$jdk_include" \
    -I"$jdk_include/linux" \
    "$linux_dir/LinuxLauncher.c" \
    "$linux_dir/LinuxPackage.c" \
    "$share_dir/JvmLauncherLib.c" \
    -pie -Wl,-z,relro -Wl,-z,now -Wl,-z,noexecstack -ldl \
    -o "$output"
