#!/usr/bin/env python3
"""Generate or check the repository's native bridge protocols."""

import argparse
from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parent.parent
MANIFEST = "util/ffi/tools/native-protocol/Cargo.toml"
PROTOCOLS = {
    "crypto": (
        "util/crypto/schema/native_crypto.proto",
        "keyguard.nativecrypto.v1.rs",
        "util/crypto/rust/crates/keyguard-crypto-core/src/protocol/generated.rs",
    ),
    "windows-passkeys": (
        "util/windows-passkeys/schema/windows_passkeys.proto",
        "keyguard.windowspasskeys.v1.rs",
        "util/windows-passkeys/rust/crates/keyguard-windows-passkeys-core/src/protocol/generated.rs",
    ),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("generate", "check"))
    parser.add_argument("protocol", choices=PROTOCOLS, nargs="?")
    args = parser.parse_args()
    protocols = [args.protocol] if args.protocol else PROTOCOLS
    for name in protocols:
        subprocess.run(
            ["cargo", "run", "--manifest-path", MANIFEST, "--locked", "--",
             args.command, *PROTOCOLS[name]],
            cwd=ROOT,
            check=True,
        )


if __name__ == "__main__":
    main()
