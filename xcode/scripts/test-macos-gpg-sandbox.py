#!/usr/bin/env python3
"""Run external GnuPG against the production Kotlin/Rust runtime in a signed sandbox.

Requires a development identity and an App Group-authorized provisioning profile.
Build :appleApp:linkDebugTestMacosArm64 and :desktopGpgAgent:compileGpgAgentUniversal
first. Pass --native-tests, --helper, --signing-identity, --provisioning-profile,
--bundle-id and --app-group. Runs only the opt-in GpgAgentSandboxProbeTest, with
disposable in-memory keys and a fresh external home; never opens the user's vault.
Run this gate on both the minimum supported macOS and macOS 27 before release.
"""

import argparse
import json
import os
from pathlib import Path
import plistlib
import selectors
import shutil
import subprocess
import tempfile
import time


def run(command, **kwargs):
    try:
        return subprocess.run(command, check=True, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, timeout=45, **kwargs).stdout
    except subprocess.CalledProcessError as error:
        raise RuntimeError(f"{command[0]} failed: {error.stderr.decode(errors='replace')}") from error


def await_manifest(process):
    pending = b""
    deadline = time.monotonic() + 120
    with selectors.DefaultSelector() as selector:
        selector.register(process.stdout, selectors.EVENT_READ)
        while time.monotonic() < deadline:
            if not selector.select(timeout=1):
                continue
            chunk = os.read(process.stdout.fileno(), 65536)
            if not chunk:
                raise RuntimeError("Native sandbox probe exited before readiness: " + pending.decode(errors="replace"))
            pending += chunk
            if len(pending) > 1024 * 1024:
                raise RuntimeError("Native sandbox probe exceeded output limit")
            while b"\n" in pending:
                line, pending = pending.split(b"\n", 1)
                if line.startswith(b"KEYGUARD_GPG_PROBE "):
                    return json.loads(line[len(b"KEYGUARD_GPG_PROBE "):])
    raise TimeoutError("Native sandbox probe did not become ready")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("native-tests", "helper", "signing-identity", "provisioning-profile", "bundle-id", "app-group"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--gpg-bin-dir", type=Path)
    parser.add_argument("--result", type=Path)
    args = parser.parse_args()
    gpg = str(args.gpg_bin_dir / "gpg") if args.gpg_bin_dir else shutil.which("gpg")
    if not gpg:
        parser.error("GnuPG must be installed")

    # Darwin's sockaddr_un permits only 103 path bytes; TMPDIR often already
    # consumes most of that budget before the GnuPG home is appended.
    with tempfile.TemporaryDirectory(prefix="kg-gpg-", dir="/private/tmp") as temporary:
        work = Path(temporary).resolve()
        app = work / "GpgSandboxProbe.app"
        contents = app / "Contents"
        executable = contents / "MacOS" / "GpgSandboxProbe"
        helper = contents / "Resources" / "keyguard-gpg-agent"
        executable.parent.mkdir(parents=True)
        helper.parent.mkdir()
        shutil.copy2(args.native_tests, executable)
        shutil.copy2(args.helper, helper)
        shutil.copy2(args.provisioning_profile, contents / "embedded.provisionprofile")
        (contents / "Info.plist").write_bytes(plistlib.dumps({
            "CFBundleIdentifier": args.bundle_id,
            "CFBundleExecutable": executable.name,
            "CFBundleName": "GpgSandboxProbe",
            "CFBundlePackageType": "APPL",
            "CFBundleVersion": "1",
            "KeyguardAppGroupIdentifier": args.app_group,
        }))
        helper_entitlements = work / "helper.plist"
        helper_entitlements.write_bytes(plistlib.dumps({
            "com.apple.security.app-sandbox": True,
            "com.apple.security.inherit": True,
        }))
        host_entitlements = work / "host.plist"
        host_entitlements.write_bytes(plistlib.dumps({
            "com.apple.security.app-sandbox": True,
            "com.apple.security.application-groups": [args.app_group],
        }))
        for target, entitlements in ((helper, helper_entitlements), (app, host_entitlements)):
            run(["codesign", "--force", "--options", "runtime", "--timestamp=none",
                 "--sign", args.signing_identity, "--entitlements", str(entitlements), str(target)])
        run(["codesign", "--verify", "--deep", "--strict", str(app)])
        env = dict(os.environ, KEYGUARD_GPG_SANDBOX_PROBE="1")
        process = subprocess.Popen([str(executable), "--ktest_filter=*GpgAgentSandboxProbeTest*"],
                                   env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT)
        manifest = None
        try:
            manifest = await_manifest(process)
            # Both GnuPG's home and the App Group socket contain spaces. Include
            # shell metacharacters too, exercising the copied command's quoting.
            home = work / "home with spaces ' and $(literal)"
            home.mkdir(mode=0o700)
            client_env = dict(os.environ, HOME=str(home), GNUPGHOME=str(home / ".keyguard" / "gnupg"))
            if args.gpg_bin_dir:
                client_env["PATH"] = str(args.gpg_bin_dir) + os.pathsep + client_env.get("PATH", "")
            for _ in range(2):
                run(["/bin/sh"], input=manifest["setup"].encode(), env=client_env)
            assert not (home / ".gnupg").exists(), "Setup touched the default GnuPG home"
            key_data = "\n".join(key["publicKey"] for key in manifest["keys"]).encode()
            command = [gpg, "--batch", "--no-autostart"]
            run(command + ["--import"], input=key_data, env=client_env)
            listed = run(command + ["--with-colons", "--list-secret-keys"], env=client_env)
            for index, key in enumerate(manifest["keys"]):
                fingerprint = key["fingerprint"]
                assert fingerprint.encode() in listed, "Public-key catalog was not exposed to GnuPG"
                message = f"Native sandbox GPG round trip {index}\n".encode()
                signed = run(command + ["--local-user", fingerprint, "--clearsign"], input=message, env=client_env)
                run(command + ["--verify"], input=signed, env=client_env)
                encrypted = run(command + ["--trust-model", "always", "--recipient", fingerprint, "--encrypt"],
                                input=message, env=client_env)
                decrypted = run(command + ["--decrypt"], input=encrypted, env=client_env)
                assert decrypted == message, "Decryption did not recover the original message"
            output = process.communicate(input=b"x", timeout=15)[0]
            assert process.returncode == 0, output.decode(errors="replace")
            result = {
                "passed": True,
                "macOS": run(["sw_vers", "-productVersion"]).decode().strip(),
                "gnupg": run([gpg, "--version"]).decode().splitlines()[0],
                "algorithms": ["Ed25519/X25519", "RSA3072", "RSA4096"],
                "setupRuns": 2,
                "signVerifyEncryptDecryptRoundTrips": len(manifest["keys"]),
                "productionKotlinRuntime": True,
                "provisionedSandbox": True,
            }
            text = json.dumps(result, indent=2) + "\n"
            if args.result:
                args.result.write_text(text)
            print(text, end="")
        finally:
            if process.poll() is None:
                try:
                    # EOF makes the test cancel its runtime and reap the helper.
                    if process.stdin and not process.stdin.closed:
                        process.stdin.close()
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


if __name__ == "__main__":
    main()
