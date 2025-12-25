"""
Script to extract the runtime-version property from a Flatpak manifest YAML file.
Outputs only the value to stdout, exits with error if property is not found.
"""

import argparse
import sys
import yaml


def parse_runtime_version(path: str) -> str:
    # Load and parse the YAML file
    with open(path, mode="r", encoding="utf-8") as file:
        data = yaml.safe_load(file)
    # Validate that we got a dictionary (YAML object)
    if not isinstance(data, dict):
        raise ValueError("Invalid manifest format: expected YAML object")
    
    # Extract and validate the value
    if "runtime-version" not in data:
        raise ValueError("runtime-version not found in manifest")
    value = data["runtime-version"]
    if value is None or str(value).strip() == "":
        raise ValueError("runtime-version is empty")
    return str(value)


def main() -> None:
    parser = argparse.ArgumentParser("parse_runtime_version")
    parser.add_argument("manifest", help="Path to Flatpak manifest", type=str)
    args = parser.parse_args()

    try:
        value = parse_runtime_version(args.manifest)
    except Exception as exc:
        # Print error to stderr and exit with non-zero code
        print(str(exc), file=sys.stderr)
        sys.exit(1)

    # Output only the value to stdout
    print(value)


if __name__ == "__main__":
    main()
