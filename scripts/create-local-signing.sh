#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p .signing
umask 077
if [ -f .signing/local-release.p12 ]; then
  if [ ! -f .signing/local-release.properties ]; then
    : "${YUNO_LOCAL_STORE_PASSWORD:?Set the existing keystore password to restore its local credentials file.}"
    printf 'storePassword=%s\nkeyPassword=%s\n' "$YUNO_LOCAL_STORE_PASSWORD" "$YUNO_LOCAL_STORE_PASSWORD" > .signing/local-release.properties
  fi
  exit 0
fi
if [ ! -f .signing/local-release.p12 ]; then
  task_signing_password="$(openssl rand -hex 24)"
  export YUNO_SIGNING_TASK_PASSWORD="$task_signing_password"
  printf 'storePassword=%s\nkeyPassword=%s\n' "$task_signing_password" "$task_signing_password" > .signing/local-release.properties
  "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" -genkeypair \
    -keystore .signing/local-release.p12 -storetype PKCS12 -alias yunotv \
    -storepass:env YUNO_SIGNING_TASK_PASSWORD -keypass:env YUNO_SIGNING_TASK_PASSWORD -keyalg RSA -keysize 3072 \
    -validity 10000 -dname "CN=Yuno TV Local, O=Yuno TV, C=CN"
  unset YUNO_SIGNING_TASK_PASSWORD task_signing_password
fi
