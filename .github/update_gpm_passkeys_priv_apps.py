import json
import requests

URL_APPS = "https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json"

response = requests.get(
    URL_APPS,
)

json_obj = response.json()
json_text = json.dumps(json_obj, indent=2)

with open('common/src/commonMain/resources/MR/files/gpm_passkeys_privileged_apps.json', 'w') as f:
    f.write(json_text)
