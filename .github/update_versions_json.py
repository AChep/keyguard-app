import argparse
import json
import logging
import os
import sys
import tempfile
from datetime import date

LOG_FORMAT = "%(asctime)s - %(levelname)s - %(message)s"

logging.basicConfig(level=logging.INFO, format=LOG_FORMAT, datefmt="%H:%M:%S")
logger = logging.getLogger(__name__)


def _read_text_file(path: str | None) -> str | None:
    if not path:
        return None

    if not os.path.exists(path):
        return None

    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def _read_versions_json(path: str):
    if not os.path.exists(path):
        logger.error(f"JSON file '{path}' not found.")
        sys.exit(1)

    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except json.JSONDecodeError as e:
        logger.error(f"Failed to parse JSON file '{path}': {e}")
        sys.exit(1)

    if not isinstance(data, list):
        logger.error(f"Expected JSON root to be a list in '{path}'.")
        sys.exit(1)

    return data


def _contains_pair(items, semantic_version: str, tag_version: str) -> bool:
    for item in items:
        if not isinstance(item, dict):
            continue
        version = item.get("version")
        if not isinstance(version, dict):
            continue
        if version.get("semantic") == semantic_version and version.get("tag") == tag_version:
            return True
    return False


def _write_versions_json(path: str, items) -> None:
    content = json.dumps(items, ensure_ascii=False, indent=4) + "\n"

    parent_dir = os.path.dirname(os.path.abspath(path)) or "."
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            delete=False,
            dir=parent_dir,
            prefix=os.path.basename(path) + ".",
            suffix=".tmp",
        ) as tmp:
            tmp.write(content)
            tmp_path = tmp.name
        os.replace(tmp_path, path)
    except OSError as e:
        logger.error(f"Failed to write JSON file '{path}': {e}")
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(
        description="Update versions JSON by appending a release entry if missing."
    )
    parser.add_argument("json_file", help="Path to the JSON file to update")
    parser.add_argument("--date", help="Release date (ISO-8601, e.g. 2025-12-30)")
    parser.add_argument("--semantic_version", help="Semantic version (e.g. 2.0.3)")
    parser.add_argument("--tag_version", help="Tag version (e.g. r20251230.1)")
    parser.add_argument(
        "--changelog_summary_file",
        default=None,
        help="Path to the changelog summary file (optional)",
    )
    parser.add_argument(
        "--changelog_raw_file",
        default=None,
        help="Path to the changelog raw file (optional)",
    )
    args = parser.parse_args()

    try:
        # Validate date format early.
        date.fromisoformat(args.date)
    except ValueError:
        msg = f"Invalid date '{args.date}'. Expected ISO-8601 format like 2025-12-30."
        logger.error(msg)
        sys.exit(1)

    items = _read_versions_json(args.json_file)

    if _contains_pair(items, args.semantic_version, args.tag_version):
        msg = f"Entry for semantic={args.semantic_version} and tag={args.tag_version} already exists; nothing to do."
        logger.info(msg)
        return

    summary = _read_text_file(args.changelog_summary_file)
    summary = summary.strip() if summary else None

    raw_text = _read_text_file(args.changelog_raw_file)
    raw_text = raw_text.strip() if raw_text else None

    new_item = {
        "date": args.date,
        "version": {
            "semantic": args.semantic_version,
            "tag": args.tag_version,
        },
        "changelog": {
            "summary": summary,
            "raw": raw_text,
        },
        "url": f"https://github.com/AChep/keyguard-app/releases/tag/{args.tag_version}",
    }

    items.insert(0, new_item)
    _write_versions_json(args.json_file, items)
    logger.info(f"Successfully updated '{args.json_file}'.")


if __name__ == "__main__":
    main()
