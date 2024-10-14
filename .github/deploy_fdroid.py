import requests
import re
import os
import sys
import subprocess

from bs4 import BeautifulSoup

HOST = "https://github.com"
REPO = "AChep/keyguard-app"
RELEASE_URL = f"{HOST}/{REPO}/releases/latest"

MAX_ARTIFACTS_COUNT = 5


def get_latest_release_tag():
    response = requests.get(RELEASE_URL)
    return re.match(r".*tag/([\w\.]+).*", response.url).group(1)


def fdroid_update():
    subprocess.run(["fdroid", "update"], check=True)


tag = get_latest_release_tag()
tag_filename = tag.replace(".", "-")
apk_filename = f"repo/Keyguard-{tag_filename}.apk"

# If the file already exists, then there is no
# need to download the file. We assume that the
# files are immutable.
if os.path.exists(apk_filename):
    fdroid_update()
    sys.exit(0)

#
# Delete old builds
#

existing_apks = []
with os.scandir("repo/") as d:
    for entry in d:
        if entry.name.endswith(".apk") and entry.is_file():
            existing_apks.append(entry.path)
existing_apks.sort()
existing_apks_to_delete = existing_apks[:1 - MAX_ARTIFACTS_COUNT]
for apk in existing_apks_to_delete:
    os.remove(apk)

#
# Download the latest .apk from GitHub
#

assets_url = f"{HOST}/{REPO}/releases/expanded_assets/{tag}"
assets_response = requests.get(assets_url)
assets_soup = BeautifulSoup(
    assets_response.content,
    features="html.parser"
)

assets_urls = [el['href'] for el in assets_soup.select('a[href]')]
assets_apk_url = next(
    filter(
        lambda url: url.endswith('/androidApp-none-release.apk'),
        assets_urls
    ),
    None
)
if not assets_apk_url:
    raise Exception("Failed to find a url to the latest .apk file!")
assets_apk_url = f"{HOST}{assets_apk_url}"

apk_response = requests.get(assets_apk_url)
with open(apk_filename, mode="wb") as file:
    file.write(apk_response.content)

#
# Update repository
#

fdroid_update()
