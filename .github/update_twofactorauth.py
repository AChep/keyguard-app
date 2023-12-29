import re
import json

from io import BytesIO
from zipfile import ZipFile
from urllib.request import urlopen

URL_2FA = "https://github.com/2factorauth/twofactorauth/archive/refs/heads/master.zip"

# Load the zip file without saving it into a file.
response = urlopen(URL_2FA)
archive = ZipFile(BytesIO(response.read()))

aggr = []

# Load each of the json files and save into
# a single json array.
for file in archive.namelist():
    if not re.match(r".+/entries/.+\.json", file):
        continue
    json_text = archive.read(file).decode('utf-8')
    json_obj = json.loads(json_text)
    # Flatten the structure.
    json_obj_root_key = list(json_obj.keys())[0]
    json_obj = json_obj[json_obj_root_key]
    # Add name
    if not "name" in json_obj:
        json_obj["name"] = json_obj_root_key
    # Delete unused attributes.
    if not "tfa" in json_obj:
        continue
    if "img" in json_obj:
        del json_obj["img"]
    if "recovery" in json_obj:
        del json_obj["recovery"]
    if "keywords" in json_obj:
        del json_obj["keywords"]
    if "categories" in json_obj:
        del json_obj["categories"]
    if "contact" in json_obj:
        del json_obj["contact"]
    if "regions" in json_obj:
        del json_obj["regions"]
    aggr.append(json_obj)

aggr_text = json.dumps(aggr, indent=2)

with open('common/src/commonMain/resources/MR/files/tfa.json', 'w') as f:
    f.write(aggr_text)
