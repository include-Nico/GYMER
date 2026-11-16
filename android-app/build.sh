#!/usr/bin/env bash
# Costruisce l'APK di GYMER. Esegui dalla radice del repo: bash android-app/build.sh
set -euo pipefail
cd "$(dirname "$0")"
ROOT=..
rm -rf www android out
mkdir -p www out
cp "$ROOT/index.html" www/

# questi file sono facoltativi per l'app: si copiano solo se esistono nel repo
for f in manifest.webmanifest favicon.ico sw.js; do
  if [ -f "$ROOT/$f" ]; then cp "$ROOT/$f" www/; else echo "Nota: $f non trovato, salto"; fi
done

mkdir -p www/icons
if [ -d "$ROOT/icons" ]; then cp -r "$ROOT/icons/." www/icons/; else echo "Nota: cartella icons non trovata, icona predefinita"; fi

# Assicura che Pillow (python3-pil) sia installato sul runner CI
if ! python3 -c "import PIL" 2>/dev/null; then
  sudo apt-get update -qq && sudo apt-get install -y -qq python3-pil
fi

npm install --no-audit --no-fund
npx cap add android
npx cap sync android
python3 patch_android.py

echo "--- file nativi usati nella build ---"
ls -la native/*.java
grep -n "class TimerState" native/TimerState.java || echo "ATTENZIONE: TimerState.java non trovato"

# firma stabile: stessa chiave a ogni build, cosi' l'APK si aggiorna sopra quello gia' installato
mkdir -p "$HOME/.android"
cp debug.keystore "$HOME/.android/debug.keystore"
( cd android && chmod +x gradlew && ./gradlew assembleDebug --no-daemon )
cp android/app/build/outputs/apk/debug/app-debug.apk out/GYMER.apk
ls -la out/GYMER.apk
