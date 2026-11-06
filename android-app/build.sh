#!/usr/bin/env bash
# Costruisce l'APK di GYMER. Esegui dalla radice del repo: bash android-app/build.sh
set -euo pipefail
cd "$(dirname "$0")"
ROOT=..
rm -rf www android out
mkdir -p www out
cp "$ROOT/index.html" "$ROOT/manifest.webmanifest" "$ROOT/favicon.ico" www/
[ -f "$ROOT/sw.js" ] && cp "$ROOT/sw.js" www/
cp -r "$ROOT/icons" www/icons
npm install --no-audit --no-fund
npx cap add android
python3 patch_android.py
npx cap sync android
# firma stabile: stessa chiave a ogni build, cosi' l'APK si aggiorna sopra quello gia' installato
mkdir -p "$HOME/.android"
cp debug.keystore "$HOME/.android/debug.keystore"
( cd android && chmod +x gradlew && ./gradlew assembleDebug --no-daemon )
cp android/app/build/outputs/apk/debug/app-debug.apk out/GYMER.apk
ls -la out/GYMER.apk
