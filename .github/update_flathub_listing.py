import argparse
import logging
import copy
import sys
import os

from bs4 import BeautifulSoup

from listing_texts import (
    LANGUAGE_BASE,
    LISTING_COMMON_DIR,
    LISTING_COMMON_FILE,
    REPO_DIR,
    list_locales,
    load_strings,
    merge,
    translated_locales,
)

LOG_FORMAT = "%(asctime)s - %(levelname)s - %(message)s"

logging.basicConfig(level=logging.INFO, format=LOG_FORMAT, datefmt="%H:%M:%S")
logger = logging.getLogger(__name__)

metadata_file = REPO_DIR / "desktopApp" / "flatpak" / "com.artemchep.keyguard.metainfo.xml"

# Load arguments
parser = argparse.ArgumentParser(
    description='Update the Flathub listing with a releases block.'
)
parser.add_argument('--releases-file', help='Path to the file containing the <releases> block')
parser.add_argument('--output', help='Path to write the listing to, instead of updating it in place')
args = parser.parse_args()


language_mapping = {
    'uk-UA': 'uk',
    'ca-ES': 'ca',
    'vi-VN': 'vi',
    'ar-SA': 'ar',
    'ro-RO': 'ro',
}

text_library_base = load_strings(LISTING_COMMON_DIR / LANGUAGE_BASE / LISTING_COMMON_FILE)
text_libraries = {}

for f in list_locales(LISTING_COMMON_DIR):
    # A text that a language does not translate is
    # taken from the base listing.
    text_library = merge(
        text_library_base,
        load_strings(LISTING_COMMON_DIR / f / LISTING_COMMON_FILE),
    )
    # We check if this listing is different from the base
    # listing. If so, we do not want to add it.
    if text_library == text_library_base:
        continue
    # Fix language tag.
    bcp_47_tag = f.replace("-r", "-")
    flathub_tag = language_mapping.get(bcp_47_tag, bcp_47_tag)
    # Append.
    text_libraries[flathub_tag] = text_library

logger.info("Loaded text libraries:")
logger.info("  📜  en (base)")
for language in text_libraries.keys():
    logger.info("  📜  " + language)
logger.info("…done!")

with open(metadata_file, 'r', encoding='utf-8') as file:
    metadata_text = file.read()
soup = BeautifulSoup(metadata_text, features="xml")

#
# Localize:
# Replace all 'keyguard_text_res' elements
#

# AppStream builds the description of a language only from the
# elements of that language: it does not take a missing paragraph
# or list item from the default language. A language that translates
# a part of the description therefore gets all of its elements.
description_el = soup.find('description')
description_languages = translated_locales(
    [target['token'] for target in description_el.find_all('keyguard_text_res')],
    text_library_base,
    text_libraries,
)

logger.info("Localizing:")
for target in soup.find_all('keyguard_text_res'):
    parent = target.parent
    token = target['token']
    in_description = target.find_parent('description') is not None

    # Add a base translation token, using the English as
    # the default language.
    new_base_el = copy.copy(parent)
    new_base_el.clear()
    new_base_el.string = text_library_base[token]

    new_elements = [new_base_el]
    for language in text_libraries.keys():
        new_loc_el = copy.copy(parent)
        new_loc_el.clear()
        new_loc_el.string = text_libraries[language][token]
        if in_description:
            if language not in description_languages:
                continue
        elif new_loc_el.string == new_base_el.string:
            continue
        new_loc_el['xml:lang'] = language
        new_elements.append(new_loc_el)
    # Replace the original parent with the new list of translated elements
    # We insert the new tags after the parent, then remove the parent
    last_element = parent
    for new_el in new_elements:
        last_element.insert_after(new_el)
        last_element = new_el
    parent.extract()

    logger.info("  ✅  " + token)
logger.info("…done!")


#
# Releases:
# Replace the 'keyguard_releases' element
#


def read_releases(releases_file):
    """Read the releases content from the input file."""
    if not os.path.exists(releases_file):
        logger.error(f"Releases file '{releases_file}' not found.")
        sys.exit(1)

    with open(releases_file, 'r', encoding='utf-8') as f:
        return f.read().strip()


def read_releases_as_bs4(releases_file):
    releases_text = read_releases(releases_file)
    return BeautifulSoup(releases_text, features="xml")


logger.info("Replacing releases:")
releases_el = soup.find('keyguard_releases')
if releases_el:
    if args.releases_file:
        replacement_soup = read_releases_as_bs4(args.releases_file)
        replacement_node = replacement_soup.find()
        if replacement_node:
            # Swap the old element with the new structure
            releases_el.replace_with(replacement_node)
            logger.info("  ✅")
    else:
        # Manual builds do not supply release history.
        releases_el.decompose()
logger.info("…done!")


# Write back to the file
logger.info("Writing to the file:")
with open(args.output or metadata_file, 'w', encoding='utf-8') as f:
    f.write(soup.decode(formatter="minimal"))
    logger.info("  ✅")
logger.info("…done!")
