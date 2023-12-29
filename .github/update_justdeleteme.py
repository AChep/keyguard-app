import json
import requests

URL_JDM = "https://raw.githubusercontent.com/jdm-contrib/jdm/master/_data/sites.json"

response = requests.get(
    URL_JDM,
)

aggr = []

for site in response.json():
    entry = {
        'name': site['name'],
        'domains': site.get('domains', []),
    }

    def append_if_not_empty(src_key, dst_key):
        if src_key in site and site[src_key]:
            entry[dst_key] = site[src_key]

    append_if_not_empty('url', 'url')
    append_if_not_empty('difficulty', 'difficulty')
    append_if_not_empty('notes', 'notes')
    # for the extra-asshole websites
    append_if_not_empty('email', 'email')
    append_if_not_empty('email_subject', 'email_subject')
    append_if_not_empty('email_body', 'email_body')

    aggr.append(entry)

aggr_text = json.dumps(aggr, indent=2)

with open('common/src/commonMain/resources/MR/files/justdeleteme.json', 'w') as f:
    f.write(aggr_text)
