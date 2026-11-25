#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "Usage: sign-release-apk.sh INPUT.apk OUTPUT.apk" >&2
    exit 2
fi

: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK path}"
: "${ANDROID_SIGNING_KEYSTORE:?Set ANDROID_SIGNING_KEYSTORE to the signing keystore path}"
: "${ANDROID_SIGNING_PASSWORD:?Set ANDROID_SIGNING_PASSWORD}"
: "${ANDROID_SIGNING_CERT_SHA256:?Set ANDROID_SIGNING_CERT_SHA256 to the certificate fingerprint}"

if [[ ! "$ANDROID_SIGNING_CERT_SHA256" =~ ^[0-9a-fA-F]{64}$ ]]; then
    echo "The configured certificate fingerprint must be 64 hexadecimal characters." >&2
    exit 2
fi

input=$1
output=$2
build_tools="$ANDROID_HOME/build-tools/35.0.0"
umask 077
scratch=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/stealth-link-sign.XXXXXXXX")
trap 'rm -f -- "$scratch/aligned.apk" "$scratch/signed.apk" "$scratch/verification.txt"; rmdir -- "$scratch"' EXIT

"$build_tools/zipalign" -f -P 16 4 "$input" "$scratch/aligned.apk"
"$build_tools/apksigner" sign \
    --ks "$ANDROID_SIGNING_KEYSTORE" \
    --ks-key-alias stealth-link \
    --ks-pass env:ANDROID_SIGNING_PASSWORD \
    --key-pass env:ANDROID_SIGNING_PASSWORD \
    --v1-signing-enabled false \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --v4-signing-enabled false \
    --out "$scratch/signed.apk" "$scratch/aligned.apk"

"$build_tools/apksigner" verify --verbose --print-certs "$scratch/signed.apk" > "$scratch/verification.txt"
"$build_tools/zipalign" -c -P 16 4 "$scratch/signed.apk"
actual=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$scratch/verification.txt")
if [[ "${actual,,}" != "${ANDROID_SIGNING_CERT_SHA256,,}" ]]; then
    echo "Signing certificate does not match the configured release fingerprint." >&2
    exit 1
fi

mkdir -p -- "$(dirname -- "$output")"
mv -- "$scratch/signed.apk" "$output"
printf 'Verified release certificate SHA-256 %s\n' "$actual"
