import argparse
import sys
from pathlib import Path

from listing_texts import (
    LANGUAGE_BASE,
    LISTING_COMMON_DIR,
    LISTING_COMMON_FILE,
    REPO_DIR,
    list_locales,
    load_locale_strings,
    load_strings,
    merge,
    render_text_template,
)

LISTING_GOOGLE_DIR = REPO_DIR / "listing" / "google"
LISTING_GOOGLE_FILE = "store_text.xml"
DESCRIPTION_TEMPLATE_FILE = LISTING_GOOGLE_DIR / "store_description.html"

PACKAGE_NAME = "com.artemchep.keyguard"

# The base texts are uploaded as the listing
# of this language.
LANGUAGE_DEFAULT = "en-US"
LANGUAGE_MAPPING = {
    'af-ZA': 'af',
    'sr-SP': 'sr',
    'uk-UA': 'uk',
    'ca-ES': 'ca',
    'vi-VN': 'vi',
    'ar-SA': 'ar',
    'ro-RO': 'ro',
}

# The longest texts, in characters, that Google Play accepts.
LISTING_LIMITS = {
    'title': 30,
    'shortDescription': 80,
    'fullDescription': 4000,
}


def get_language_tag(folder):
    bcp_47_tag = folder.replace("-r", "-")
    return LANGUAGE_MAPPING.get(bcp_47_tag, bcp_47_tag)


def compose_listing(store_texts, common_texts, description_template):
    return {
        'title': store_texts["listing_store_google_app_name"],
        'shortDescription': store_texts["listing_store_google_summary"],
        'fullDescription': render_text_template(description_template, common_texts),
    }


"""
Load the listings from the folders to a human-accessible
dictionary object, keyed by the Google Play language tag.

A text that a language does not translate is taken from
the base listing.
"""


def load_listings():
    description_template = DESCRIPTION_TEMPLATE_FILE.read_text(encoding="utf-8")
    store_texts_base = load_strings(LISTING_GOOGLE_DIR / LANGUAGE_BASE / LISTING_GOOGLE_FILE)
    common_texts_base = load_strings(LISTING_COMMON_DIR / LANGUAGE_BASE / LISTING_COMMON_FILE)

    listings = {
        LANGUAGE_DEFAULT: compose_listing(
            store_texts_base,
            common_texts_base,
            description_template,
        ),
    }
    for folder in list_locales(LISTING_GOOGLE_DIR):
        language = get_language_tag(folder)
        store_texts = load_locale_strings(LISTING_GOOGLE_DIR, folder, LISTING_GOOGLE_FILE)
        common_texts = load_locale_strings(LISTING_COMMON_DIR, folder, LISTING_COMMON_FILE)
        listings[language] = compose_listing(
            merge(store_texts_base, store_texts),
            merge(common_texts_base, common_texts),
            description_template,
        )
    return listings


def get_listing_violations(listing):
    return [
        f"{field} is {len(listing[field])} characters long, the limit is {limit}"
        for field, limit in LISTING_LIMITS.items()
        if len(listing[field]) > limit
    ]


"""
Remove the listings that Google Play would reject. A translation that is
too long keeps its current listing in the store; the default listing has
to fit.
"""


def filter_listings(listings):
    violations = get_listing_violations(listings[LANGUAGE_DEFAULT])
    if violations:
        sys.exit(f"The {LANGUAGE_DEFAULT} listing is too long: " + "; ".join(violations))

    valid_listings = {}
    for language, listing in listings.items():
        violations = get_listing_violations(listing)
        if violations:
            print(f"::warning title=Google Play listing::Skipped {language}: " + "; ".join(violations))
            continue
        valid_listings[language] = listing
    return valid_listings


def print_listings(listings):
    print("Loaded listings for languages:")
    for language, listing in listings.items():
        lengths = ", ".join(
            f"{field} {len(listing[field])}/{limit}"
            for field, limit in LISTING_LIMITS.items()
        )
        print(f"- {language}: {lengths}")
    print()


def write_listings(listings, output_dir):
    for language, listing in listings.items():
        language_dir = Path(output_dir) / language
        language_dir.mkdir(parents=True, exist_ok=True)
        for field in LISTING_LIMITS.keys():
            (language_dir / f"{field}.txt").write_text(listing[field] + "\n", encoding="utf-8")


def upload_listings(listings):
    import httplib2

    from googleapiclient.discovery import build
    from googleapiclient.errors import (
        HttpError,
    )

    from oauth2client.service_account import ServiceAccountCredentials

    credentials = ServiceAccountCredentials.from_json_keyfile_name(
        "service-account-google.json",
        scopes="https://www.googleapis.com/auth/androidpublisher",
    )

    # Create an httplib2.Http object to handle our HTTP requests and authorize
    # it with the Credentials.
    http = httplib2.Http()
    http = credentials.authorize(http)

    service = build("androidpublisher", "v3", http=http)

    edit = service.edits().insert(packageName=PACKAGE_NAME).execute()
    edit_id = edit["id"]

    # Keep the replaced texts in the log, so a listing that
    # was edited in the Play Console can be restored.
    #
    # See:
    # https://developers.google.com/android-publisher/api-ref/rest/v3/edits.listings/list
    live_listings = service.edits()\
        .listings()\
        .list(
            packageName=PACKAGE_NAME,
            editId=edit_id,
    ).execute()
    print("::group::Replaced listings")
    for live_listing in live_listings.get("listings", []):
        print("- " + live_listing["language"])
        for field in LISTING_LIMITS.keys():
            print(f"{field}:")
            print(live_listing.get(field, ""))
        print()
    print("::endgroup::")

    print("Uploading:")
    for language, listing in listings.items():
        listing_request = {
            'language': language,
            'title': listing["title"],
            'shortDescription': listing["shortDescription"],
            'fullDescription': listing["fullDescription"],
        }
        try:
            # See:
            # https://developers.google.com/android-publisher/api-ref/rest/v3/edits.listings/patch
            service.edits()\
                .listings()\
                .patch(
                    packageName=PACKAGE_NAME,
                    editId=edit_id,
                    language=language,
                    body=listing_request,
            ).execute()
        except HttpError as e:
            if e.status_code == 404:
                # See:
                # https://developers.google.com/android-publisher/api-ref/rest/v3/edits.listings/update
                service.edits()\
                    .listings()\
                    .update(
                        packageName=PACKAGE_NAME,
                        editId=edit_id,
                        language=language,
                        body=listing_request,
                ).execute()
            else:
                raise e
        print("- " + language + " done!")

    service.edits()\
        .commit(
        packageName=PACKAGE_NAME,
        editId=edit_id,
    ).execute()

    print("Success!")


def main():
    parser = argparse.ArgumentParser(
        description='Upload the Google Play Store listing.'
    )
    parser.add_argument('--dry-run', action='store_true', help='Compose the listings without uploading them')
    parser.add_argument('--output-dir', help='Path to the directory to write the composed listings to')
    args = parser.parse_args()

    listings = filter_listings(load_listings())
    print_listings(listings)
    if args.output_dir:
        write_listings(listings, args.output_dir)
    if not args.dry_run:
        upload_listings(listings)


if __name__ == "__main__":
    main()
