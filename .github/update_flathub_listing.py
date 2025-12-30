import argparse
import logging
import sys
import os

LOG_FORMAT = "%(asctime)s - %(levelname)s - %(message)s"

logging.basicConfig(level=logging.INFO, format=LOG_FORMAT, datefmt="%H:%M:%S")
logger = logging.getLogger(__name__)


def read_releases(releases_file):
    """Read the releases content from the input file."""
    if not os.path.exists(releases_file):
        logger.error(f"Releases file '{releases_file}' not found.")
        sys.exit(1)
    
    with open(releases_file, 'r', encoding='utf-8') as f:
        return f.read().strip()


def update_listing(metadata_file, releases_content):
    """Inject releases content into the metadata file."""
    if not os.path.exists(metadata_file):
        logger.error(f"Metadata file '{metadata_file}' not found.")
        sys.exit(1)
    
    with open(metadata_file, 'r', encoding='utf-8') as f:
        content = f.read()
    placeholder = "<!-- GENERATED RELEASES BLOCK -->"
    if placeholder not in content:
        logger.error(f"Placeholder '{placeholder}' not found in metadata file.")
        sys.exit(1)
    
    # Inject the releases content
    updated_content = content.replace(placeholder, releases_content)
    
    # Write back to the file
    with open(metadata_file, 'w', encoding='utf-8') as f:
        f.write(updated_content)
    
    logger.info(f"Successfully updated listing in {metadata_file}")


def main():
    parser = argparse.ArgumentParser(
        description='Update the Flathub listing with a releases block.'
    )
    parser.add_argument('--releases-file', help='Path to the file containing the <releases> block')
    args = parser.parse_args()
    
    metadata_file = "desktopApp/flatpak/com.artemchep.keyguard.metainfo.xml"
    
    # Read releases content and
    # update metadata file
    releases_content = read_releases(args.releases_file)
    update_listing(metadata_file, releases_content)


if __name__ == "__main__":
    main()
