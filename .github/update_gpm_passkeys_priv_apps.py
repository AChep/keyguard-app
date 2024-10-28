import json
import requests

URL_APPS = "https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json"

EXTRA_APPS = [
    {
        "type": "android",
        "info": {
            "package_name": "org.chromium.chrome",
            "signatures": [
                {
                    "build": "release",
                    "cert_fingerprint_sha256": "A8:56:48:50:79:BC:B3:57:BF:BE:69:BA:19:A9:BA:43:CD:0A:D9:AB:22:67:52:C7:80:B6:88:8A:FD:48:21:6B"
                }
            ]
        }
    },
    {
        "type": "android",
        "info": {
            "package_name": "org.cromite.cromite",
            "signatures": [
                {
                    "build": "release",
                    "cert_fingerprint_sha256": "63:3F:A4:1D:82:11:D6:D0:91:6A:81:9B:89:66:8C:6D:E9:2E:64:23:2D:A6:7F:9D:16:FD:81:C3:B7:E9:23:FF"
                }
            ]
        }
    },
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
