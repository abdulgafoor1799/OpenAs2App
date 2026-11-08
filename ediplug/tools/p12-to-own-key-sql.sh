#!/bin/sh
# One-off import helper: converts key pairs from an existing PKCS12 keystore into SQL upserts for as2_own_key.
# The private key is re-encrypted as an encrypted PKCS#8 PEM with AES-256-CBC / PBKDF2-HMAC-SHA256.
# Plaintext key material only ever exists in /tmp, so run it in a throwaway container with /tmp on tmpfs:
#
#   docker run --rm --tmpfs /tmp -e P12_PASSWORD -e AS2_KEY_ENCRYPTION_PASSWORD -v $PWD/ediplug/tools:/tools:ro \
#     -v /path/to/keystore.p12:/in.p12:ro --entrypoint sh openas2:<tag> /tools/p12-to-own-key-sql.sh /in.p12 alias1 alias2 \
#     | psql -v ON_ERROR_STOP=1 -d openas2
#
# Env: P12_PASSWORD (source keystore), AS2_KEY_ENCRYPTION_PASSWORD (passphrase OpenAS2 decrypts the keys with).
set -eu
P12=$1; shift
: "${P12_PASSWORD:?}" "${AS2_KEY_ENCRYPTION_PASSWORD:?}"
umask 077
W=$(mktemp -d /tmp/ownkey.XXXXXX)
trap 'rm -rf "$W"' EXIT
echo "BEGIN;"
for ALIAS in "$@"; do
  keytool -importkeystore -noprompt -srckeystore "$P12" -srcstoretype PKCS12 -srcstorepass "$P12_PASSWORD" \
    -srcalias "$ALIAS" -destkeystore "$W/one.p12" -deststoretype PKCS12 -deststorepass "$P12_PASSWORD" \
    -destkeypass "$P12_PASSWORD" >/dev/null 2>&1
  openssl pkcs12 -in "$W/one.p12" -passin env:P12_PASSWORD -nocerts -nodes 2>/dev/null \
    | openssl pkcs8 -topk8 -v2 aes-256-cbc -v2prf hmacWithSHA256 -passout env:AS2_KEY_ENCRYPTION_PASSWORD > "$W/key.pem"
  # Leaf first: keytool keeps the chain order of the entry
  keytool -list -rfc -keystore "$W/one.p12" -storetype PKCS12 -storepass "$P12_PASSWORD" -alias "$ALIAS" \
    | sed -n '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/p' > "$W/chain.pem"
  openssl x509 -in "$W/chain.pem" -noout >/dev/null
  FP=$(openssl x509 -in "$W/chain.pem" -noout -fingerprint -sha256 | cut -d= -f2)
  SUBJ=$(openssl x509 -in "$W/chain.pem" -noout -subject -nameopt RFC2253 | sed 's/^subject=//; s/'"'"'/'"''"'/g')
  NB=$(openssl x509 -in "$W/chain.pem" -noout -startdate | cut -d= -f2)
  NA=$(openssl x509 -in "$W/chain.pem" -noout -enddate | cut -d= -f2)
  printf "INSERT INTO as2_own_key (alias, private_key_pem, cert_chain_pem, sha256_fingerprint, subject_dn, not_before, not_after)\n"
  printf "VALUES ('%s', \$k\$%s\$k\$, \$c\$%s\$c\$, '%s', '%s', '%s', '%s')\n" "$ALIAS" "$(cat "$W/key.pem")" "$(cat "$W/chain.pem")" "$FP" "$SUBJ" "$NB" "$NA"
  printf "ON CONFLICT (alias) DO UPDATE SET private_key_pem = EXCLUDED.private_key_pem, cert_chain_pem = EXCLUDED.cert_chain_pem,\n"
  printf "  sha256_fingerprint = EXCLUDED.sha256_fingerprint, subject_dn = EXCLUDED.subject_dn, not_before = EXCLUDED.not_before,\n"
  printf "  not_after = EXCLUDED.not_after, updated_at = now();\n"
  rm -f "$W/one.p12" "$W/key.pem" "$W/chain.pem"
done
echo "COMMIT;"
