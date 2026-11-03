#!/usr/bin/env bash
# Local test identities only. Requires Python 3 and OpenSSL 3.4 or newer.
set -euo pipefail
umask 077

script_dir="$(cd "$(dirname "$0")" && pwd)"
openssl_bin="${OPENSSL:-openssl}"
if ! "$openssl_bin" x509 -help 2>&1 | grep -q -- '-not_after'; then
    echo 'OpenSSL 3.4+ is required. Set OPENSSL to its executable path.' >&2
    exit 1
fi
if [ -e "$script_dir/fixtures" ]; then
    echo 'fixtures already exists; keep it to preserve the running server identities.' >&2
    exit 1
fi

mkdir "$script_dir/fixtures"
cd "$script_dir/fixtures"
read -r not_before not_after < <(python3 - <<'PY'
from datetime import datetime, timezone
start = datetime.now(timezone.utc).replace(microsecond=0)
try:
    end = start.replace(year=start.year + 100)
except ValueError:
    end = start.replace(year=start.year + 100, day=28)
print(start.strftime('%Y%m%d%H%M%SZ'), end.strftime('%Y%m%d%H%M%SZ'))
PY
)

cat > extensions.cnf <<'EOF'
[ca]
basicConstraints = critical, CA:true, pathlen:0
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash

[server]
basicConstraints = critical, CA:false
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer
subjectAltName = DNS:localhost,IP:127.0.0.1,IP:::1,IP:10.0.2.2

[client]
basicConstraints = critical, CA:false
keyUsage = critical, digitalSignature
extendedKeyUsage = clientAuth
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer
EOF

"$openssl_bin" genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out ca-key.pem 2>/dev/null
"$openssl_bin" x509 -new -key ca-key.pem -sha256 -out ca.pem \
    -subj '/CN=Keyguard local mTLS test CA' \
    -not_before "$not_before" -not_after "$not_after" \
    -extfile extensions.cnf -extensions ca

issue() {
    local file="$1" name="$2" extension="$3"
    "$openssl_bin" genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$file-key.pem" 2>/dev/null
    "$openssl_bin" req -new -key "$file-key.pem" -subj "/CN=$name" -out "$file.csr"
    "$openssl_bin" x509 -req -in "$file.csr" -CA ca.pem -CAkey ca-key.pem \
        -set_serial "0x$("$openssl_bin" rand -hex 16)" -sha256 \
        -not_before "$not_before" -not_after "$not_after" \
        -extfile extensions.cnf -extensions "$extension" -out "$file.pem"
    rm "$file.csr"
}

issue server localhost server
issue client 'Keyguard test client' client
issue client-replacement 'Keyguard replacement client' client
for file in client client-replacement; do
    # 3DES/SHA-1 PKCS#12 encryption also imports into Apple's native keychain.
    "$openssl_bin" pkcs12 -export -inkey "$file-key.pem" -in "$file.pem" \
        -certfile ca.pem -name "$file" -out "$file.p12" \
        -keypbe PBE-SHA1-3DES -certpbe PBE-SHA1-3DES -macalg sha1 \
        -passout pass:keyguard-test
done

"$openssl_bin" verify -CAfile ca.pem -purpose sslserver server.pem
"$openssl_bin" verify -CAfile ca.pem -purpose sslclient client.pem client-replacement.pem
"$openssl_bin" x509 -in server.pem -noout -dates
printf '\nFixtures: %s/fixtures\nPKCS#12 password: keyguard-test\n' "$script_dir"
