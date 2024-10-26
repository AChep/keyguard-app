import argparse
import time
import requests
import json
import sys

from crowdin_api import CrowdinClient
from crowdin_api.api_resources.reports.enums import (
    Format,
)

parser = argparse.ArgumentParser("update_localization_contributors")
parser.add_argument("api_key", help="Access token", type=str)
parser.add_argument("project_id", help="Project ID", type=str)
args = parser.parse_args()


MIN_WORDS_CONTRIBUTED = 64


class MyCrowdinClient(CrowdinClient):
    TOKEN = args.api_key
    PROJECT_ID = args.project_id


client = MyCrowdinClient()

reports = client.reports

print("Requested a top members report.")
report_request_response = reports.generate_top_members_report(
    unit="words", format=Format.JSON)
report_request_id = report_request_response["data"]["identifier"]

for i in range(60):
    # Sleep for a small number of seconds
    # before querying the report status update.
    time.sleep(2)

    report_status_response = reports.check_report_generation_status(
        report_request_id)
    report_status = report_status_response["data"]["status"]
    print(f"Checking report generation status: {report_status}.")

    if report_status == "finished":
        break
else:
    sys.exit()


report_response = reports.download_report(report_request_id)
report_url = report_response["data"]["url"]
report = requests.get(report_url).json()

report_data = report["data"]
# Filter out contributors with very low
# amount of contributed words.
report_data = list(filter(
    lambda x: x["translated"] + x["approved"] >= MIN_WORDS_CONTRIBUTED, report_data))

report_data_text = json.dumps(report_data, indent=2)

with open('common/src/commonMain/composeResources/files/localization_contributors.json', 'w') as f:
    f.write(report_data_text)
