#!/usr/bin/env python3
"""Read-only native-package evidence for the installed-package startup acceptance lane.

Run in the dedicated QA login session, after the session harness has launched the
signed package. This never launches Keyguard, touches a vault, or registers login.
"""

import argparse
import hashlib
import json
import platform
import plistlib
from pathlib import Path
import subprocess
import sys


OBSERVER = r'''
import AppKit
import CoreGraphics
import Foundation
let target = URL(fileURLWithPath: CommandLine.arguments[1]).resolvingSymlinksInPath()
let session = CGSessionCopyCurrentDictionary() as? [String: Any]
let apps = NSWorkspace.shared.runningApplications.filter {
    $0.bundleURL?.resolvingSymlinksInPath() == target
}
let windows = CGWindowListCopyWindowInfo(.optionOnScreenOnly, kCGNullWindowID) as? [[String: Any]] ?? []
let pids = apps.map { Int($0.processIdentifier) }
let visible = windows.filter {
    pids.contains($0[kCGWindowOwnerPID as String] as? Int ?? -1)
        && ($0[kCGWindowLayer as String] as? Int ?? -1) == 0
        && ($0[kCGWindowAlpha as String] as? Double ?? 0) > 0
}
let value: [String: Any] = [
    "on_console": session?[kCGSessionOnConsoleKey as String] as? Bool ?? false,
    "process_ids": pids,
    "visible_window_count": visible.count,
]
let data = try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
print(String(decoding: data, as: UTF8.self))
'''


def command(*args):
    return subprocess.run(args, check=True, capture_output=True, text=False, timeout=90)


def assess(case, os_version, expected_major, signed, entitlements, group, observation):
    checks = []
    blocked = []
    failures = []
    if expected_major and os_version.split(".")[0] != str(expected_major):
        blocked.append(f"Requires macOS {expected_major}; current version is {os_version}")
    if not signed:
        blocked.append("A valid team-signed package is required; ad-hoc signing is insufficient")
    sandbox = entitlements.get("com.apple.security.app-sandbox") is True
    groups = entitlements.get("com.apple.security.application-groups", [])
    checks.append({"name": "sandbox_declared", "passed": sandbox})
    checks.append({"name": "app_group_declared", "passed": bool(group and group in groups)})
    if not sandbox or not group or group not in groups:
        failures.append("Package lacks its expected sandbox/AppGroup entitlements")
    if case != "preflight":
        if not observation or observation.get("on_console") is not True:
            blocked.append("An active macOS console session and window observation are required")
        else:
            single = len(observation.get("process_ids", [])) == 1
            visible = observation.get("visible_window_count", 0)
            expected_windows = visible == 0 if case == "quiet" else visible > 0
            checks.extend([
                {"name": "single_running_process", "passed": single},
                {"name": "expected_window_visibility", "passed": expected_windows},
            ])
            if not single or not expected_windows:
                failures.append("Running process count or window visibility differs from the scenario")
    return {
        "status": "FAIL" if failures else "BLOCKED" if blocked else "PASS",
        "checks": checks,
        "blocked_reasons": blocked,
        "failures": failures,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--case", choices=("preflight", "quiet", "visible"), required=True)
    parser.add_argument("--expected-os-major", type=int)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    report = {
        "schema": "keyguard.macos-startup-acceptance.v1",
        "case": args.case,
        "platform": "macos-native",
        "version": platform.mac_ver()[0],
        "artifact": {"path": str(args.app.resolve()), "sha256": None, "kind": "main-executable"},
        "limitations": [
            "Entitlement declarations do not prove a successful sandboxed vault bootstrap",
            "Visible windows do not prove that a hidden main root was never constructed",
            "This observation does not register login or prove a login/logout transition",
        ],
    }
    try:
        info = plistlib.loads((args.app / "Contents/Info.plist").read_bytes())
        executable = args.app / "Contents/MacOS" / info["CFBundleExecutable"]
        report["artifact"]["sha256"] = hashlib.sha256(executable.read_bytes()).hexdigest()
        signature = command("codesign", "-d", "--verbose=4", str(args.app)).stderr.decode()
        command("codesign", "--verify", "--deep", "--strict", str(args.app))
        entitlements = plistlib.loads(command("codesign", "-d", "--entitlements", ":-", str(args.app)).stdout)
        team_lines = [line for line in signature.splitlines() if line.startswith("TeamIdentifier=")]
        signed = bool(team_lines and team_lines[0] != "TeamIdentifier=not set" and "Signature=adhoc" not in signature)
        report["team_identifier"] = team_lines[0].split("=", 1)[1] if team_lines else None
        report["bundle_identifier"] = info["CFBundleIdentifier"]
        observation = None
        if args.case != "preflight":
            observation = json.loads(command("swift", "-e", OBSERVER, str(args.app.resolve())).stdout)
            report["observation"] = observation
        report.update(assess(
            args.case, report["version"], args.expected_os_major, signed, entitlements,
            info.get("KeyguardAppGroupIdentifier"), observation,
        ))
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        report.update(status="BLOCKED", checks=[], failures=[], blocked_reasons=[str(error)])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"{report['status']}: {args.output}")
    return {"PASS": 0, "FAIL": 1, "BLOCKED": 2}[report["status"]]


if __name__ == "__main__":
    sys.exit(main())
