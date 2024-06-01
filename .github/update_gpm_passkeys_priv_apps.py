import json
import requests

URL_APPS = "https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json"

EXTRA_APPS = [
    # Firefox Nightly for Developers
    # https://play.google.com/store/apps/details?id=org.mozilla.fenix
    {
      "type": "android",
      "info": {
        "package_name": "org.mozilla.fenix",
        "signatures": [
          {
            "build": "release",
            "cert_fingerprint_sha256": "50:04:77:90:88:E7:F9:88:D5:BC:5C:C5:F8:79:8F:EB:F4:F8:CD:08:4A:1B:2A:46:EF:D4:C8:EE:4A:EA:F2:11"
          }
        ]
      }
    },
]

response = requests.get(
    URL_APPS,
)

json_obj = response.json()
# Add extra apps to the resulting privileged
# apps list.
for extra in EXTRA_APPS:
    apps = json_obj["apps"]
    for app in apps:
        if app["type"] == extra["type"] and app["info"]["package_name"] == extra["info"]["package_name"]:
            break
    else:
        apps.append(extra)
json_text = json.dumps(json_obj, indent=2)

with open('common/src/commonMain/composeResources/files/gpm_passkeys_privileged_apps.json', 'w') as f:
    f.write(json_text)
