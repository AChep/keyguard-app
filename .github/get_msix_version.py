"""Derive the 4-part MSIX package version for a release tag.

The Microsoft Store rejects a package version it has already seen and
requires the 4th segment to be 0, while Keyguard ships the same semantic
version under several release tags. The 3rd segment therefore encodes
both the patch version and the ordinal of the tag within that version:

    MAJOR.MINOR.(PATCH * 100 + n).0

where n is the number of earlier releases with the same semantic version.
"""

import argparse
import json
import re
import sys

SEMANTIC_VERSION_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
TAGS_PER_PATCH = 100
MAX_SEGMENT = 65535


def count_earlier_releases(items, semantic_version: str, tag_version: str) -> int:
    count = 0
    for item in items:
        if not isinstance(item, dict):
            continue
        version = item.get("version")
        if not isinstance(version, dict):
            continue
        if version.get("semantic") == semantic_version and version.get("tag") != tag_version:
            count += 1
    return count


def get_msix_version(semantic_version: str, ordinal: int) -> str:
    match = SEMANTIC_VERSION_RE.match(semantic_version)
    if match is None:
        raise ValueError(f"Expected MAJOR.MINOR.PATCH, got '{semantic_version}'.")
    if not 0 <= ordinal < TAGS_PER_PATCH:
        raise ValueError(f"Release ordinal {ordinal} does not fit into {TAGS_PER_PATCH} slots per patch version.")

    major, minor, patch = (int(group) for group in match.groups())
    build = patch * TAGS_PER_PATCH + ordinal
    for segment in (major, minor, build):
        if segment > MAX_SEGMENT:
            raise ValueError(f"Version segment {segment} exceeds {MAX_SEGMENT}.")
    if major == 0:
        raise ValueError("The first version segment can not be 0.")
    return f"{major}.{minor}.{build}.0"


def main():
    parser = argparse.ArgumentParser(description="Print the MSIX package version for a release.")
    parser.add_argument("versions_json", help="Path to the versions.json with the release history.")
    parser.add_argument("--semantic_version", required=True, help="Semantic version, e.g. 3.2.0.")
    parser.add_argument("--tag_version", required=True, help="Release tag, e.g. r20260905.")
    args = parser.parse_args()

    with open(args.versions_json, "r", encoding="utf-8") as f:
        items = json.load(f)
    if not isinstance(items, list):
        print(f"Expected JSON root to be a list in '{args.versions_json}'.", file=sys.stderr)
        sys.exit(1)

    ordinal = count_earlier_releases(items, args.semantic_version, args.tag_version)
    try:
        print(get_msix_version(args.semantic_version, ordinal))
    except ValueError as e:
        print(str(e), file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
