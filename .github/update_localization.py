import re
import os

# Compose multiplatform treats Compose resources
# differently to how Android treats those.
#
# https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-images-resources.html
def process_file(file):
    with open(file) as f:
        content = f.read()
    content = re.sub(r"(\\)(['\"])", r"\2", content)
    with open(file, "w") as f:
        f.write(content)

for root, dirs, files in os.walk("common/src/commonMain/composeResources"):
    path = root.split(os.sep)
    dir = os.path.basename(root)
    if not 'values-' in dir:
        continue
    for file in files:
        file_path = os.path.join(root, file)
        process_file(file_path)
