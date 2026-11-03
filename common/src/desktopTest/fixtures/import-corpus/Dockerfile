# Test-only exporter environment of the import corpus; see
# common/src/desktopTest/resources/import-corpus/README.md.
FROM node:22.22-trixie-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        keepassxc \
        python3 \
        python3-argon2 \
        python3-cryptography \
    && rm -rf /var/lib/apt/lists/*

RUN npm install --global @bitwarden/cli@2026.10.0 \
    && npm cache clean --force
