import json
import requests
import argparse

parser = argparse.ArgumentParser("update_passkeys")
parser.add_argument("api_key", help="An API Key to access the https://passkeys.directory/ backend.", type=str)
args = parser.parse_args()

URL_PASSKEYS = "https://apecbgwekadegtkzpwyh.supabase.co/rest/v1/sites"
URL_APIKEY = args.api_key

response = requests.get(
    URL_PASSKEYS,
    params={
        'select': '*',
        'or': '(passkey_signin.eq.true,passkey_mfa.eq.true)',
    },
    headers={
        'apikey': URL_APIKEY,
    },
)

aggr = []

for site in response.json():
    features = []
    entry = {
        'name': site['name'],
        'domain': site['domain'],
        'features': features,
    }

    def append_if_not_empty(src_key, dst_key):
        if src_key in site and site[src_key]:
            entry[dst_key] = site[src_key]

    append_if_not_empty('documentation_link', 'documentation')
    append_if_not_empty('setup_link', 'setup')
    append_if_not_empty('notes', 'notes')

    # convert features to a list
    if site['passkey_mfa']:
        features.append('mfa')
    if site['passkey_signin']:
        features.append('signin')
    
    aggr.append(entry)

# Ensure the file is sorted for better 
# git diff logs.
aggr.sort(key=lambda x: x['domain'])

aggr_text = json.dumps(aggr, indent=2)

with open('common/src/commonMain/resources/MR/files/passkeys.json', 'w') as f:
    f.write(aggr_text)
