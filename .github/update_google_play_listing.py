import httplib2
import os

from bs4 import BeautifulSoup

from googleapiclient.discovery import build
from googleapiclient.errors import (
    HttpError,
)

from oauth2client.service_account import ServiceAccountCredentials


dir = "listing/google/"


def load_listing_full_description(folder):
    file_path = dir + folder + "/store_description.html"
    with open(file_path, 'r') as file:
        data = file.read()
    return {
        'listing_store_google_full_description': data,
    }


def load_listing_texts(folder):
    file_path = dir + folder + "/store_text.xml"
    with open(file_path, 'r') as file:
        data = file.read()
    # Parse xml to dictionary
    soup = BeautifulSoup(data, features="xml")
    return {el["name"]: el.string for el in soup.find_all('string')}


"""
Load the listings from a folder to a human-accessible
dictionary object.  
"""


def load_listing(folder):
    a = load_listing_full_description(folder)
    b = load_listing_texts(folder)
    return a | b


package_name = "com.artemchep.keyguard"

language_base = "base"
language_mapping = {
    'uk-UA': 'uk',
    'ca-ES': 'ca',
    'vi-VN': 'vi',
    'ar-SA': 'ar',
}

listing_base = load_listing(language_base)
listings = {}

for folder in os.listdir(dir):
    listing = load_listing(folder)
    # We check if this listing is different from the base
    # listing. If so, we do not want to add it.
    if listing == listing_base:
        continue
    # Fix language tag.
    bcp_47_tag = folder.replace("-r", "-")
    google_play_tag = language_mapping.get(bcp_47_tag, bcp_47_tag)
    # Append.
    listings[google_play_tag] = listing

print("Loaded listings for languages:")
for language in listings.keys():
    print("- " + language)
print()

credentials = ServiceAccountCredentials.from_json_keyfile_name(
    "service-account-google.json",
    scopes="https://www.googleapis.com/auth/androidpublisher",
)

# Create an httplib2.Http object to handle our HTTP requests and authorize
# it with the Credentials.
http = httplib2.Http()
http = credentials.authorize(http)

service = build("androidpublisher", "v3", http=http)

print("Uploading:")
edit = service.edits().insert(packageName=package_name).execute()
edit_id = edit["id"]

for language, listing in listings.items():
    listing_request = {
        'language': language,
        'title': listing["listing_store_google_app_name"],
        'shortDescription': listing["listing_store_google_short_description"],
        'fullDescription': listing["listing_store_google_full_description"],
    }
    listing_response = None
    try:
        # See:
        # https://developers.google.com/android-publisher/api-ref/rest/v3/edits.listings/patch
        listing_response = service.edits()\
            .listings()\
            .patch(
                packageName=package_name,
                editId=edit_id,
                language=language,
                body=listing_request,
        ).execute()
    except HttpError as e:
        if e.status_code == 404:
            # See:
            # https://developers.google.com/android-publisher/api-ref/rest/v3/edits.listings/update
            listing_response = service.edits()\
                .listings()\
                .update(
                    packageName=package_name,
                    editId=edit_id,
                    language=language,
                    body=listing_request,
            ).execute()
        else:
            raise e
    print("- " + language + " done!")


service.edits()\
    .commit(
    packageName=package_name,
    editId=edit_id,
).execute()

print("Success!")
