#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p .signing
if [ ! -f .signing/local-release.p12 ]; then
  "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" -genkeypair \
    -keystore .signing/local-release.p12 -storetype PKCS12 -alias yunotv \
    -storepass yunotv-local -keypass yunotv-local -keyalg RSA -keysize 3072 \
    -validity 10000 -dname "CN=Yuno TV Local, O=Yuno TV, C=CN"
fi
