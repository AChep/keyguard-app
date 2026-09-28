#!/bin/sh
# Source before Gradle runs from Xcode. Preserve the caller's toolchain selection
# and add standard Rust locations for GUI builds without shell startup files.
export PATH="${PATH:+$PATH:}${CARGO_HOME:+$CARGO_HOME/bin:}${HOME:+$HOME/.cargo/bin:}/opt/homebrew/opt/rustup/bin:/usr/local/opt/rustup/bin:/opt/homebrew/bin:/usr/local/bin"

if ! command -v cargo >/dev/null 2>&1 ||
   ! command -v rustc >/dev/null 2>&1; then
    echo "error: Rust tools not found. Install Rust using rustup or expose cargo and rustc through PATH." >&2
    return 1
fi
