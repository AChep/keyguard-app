"""The dedicated simulator: device selection and its local Files folder."""

import hashlib
import os
import subprocess
from pathlib import Path

FILES_FOLDER = "Keyguard E2E"


def device_argument(parser):
    """Add --device, defaulting to $E2E_DEVICE."""
    device = os.environ.get("E2E_DEVICE")
    parser.add_argument("--device", default=device, required=not device,
                        help="Dedicated simulator UDID, never 'booted' (default: $E2E_DEVICE)")


def files_directory(device, create=False):
    """The simulator's On My iPhone > Keyguard E2E folder."""
    groups = subprocess.run([
        "xcrun", "simctl", "get_app_container", device, "com.apple.DocumentsApp", "groups",
    ], check=True, capture_output=True, text=True).stdout
    mapping = dict(line.split("\t", 1) for line in groups.splitlines())
    target = (Path(mapping["group.com.apple.FileProvider.LocalStorage"])
              / "File Provider Storage" / FILES_FOLDER)
    if create:
        target.mkdir(parents=True, exist_ok=True)
    elif not target.is_dir():
        raise SystemExit(f"{target} does not exist. Run `e2e prepare_fixtures` first.")
    return target


def files_map(directory):
    """Size and SHA-256 of every regular file below directory."""
    return {str(p.relative_to(directory)): dict(size=p.stat().st_size, sha256=hashlib.sha256(p.read_bytes()).hexdigest())
            for p in directory.rglob("*") if p.is_file() and not p.is_symlink()}
