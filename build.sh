#!/bin/bash
# Полная сборка «Смета PRO» из исходников (aapt2 + javac + d8, без Gradle).
#
# Секреты в репозитории НЕ хранятся. Перед сборкой задайте переменные окружения
# (см. .env.example):
#   SMETA_KEYSTORE_PASSWORD — пароль от ключа подписи
#   KEYSTORE_PATH           — путь к файлу ключа (сам keystore в репозиторий не кладётся)
# Необязательно:
#   JAVA_HOME               — JDK 17 (по умолчанию ищется в ~/workspace/tools)
#   ANDROID_SDK_ROOT        — Android SDK с build-tools 34.0.0 и platform android-34
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
DIR="$ROOT/smeta-pro"
BUILD="$ROOT/build"

export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-17.0.21.0.2+1}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_SDK_ROOT:-$HOME/workspace/tools/android-sdk}"
AAPT2="$SDK/build-tools/34.0.0/aapt2"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
D8="$SDK/build-tools/34.0.0/d8"
ZIPALIGN="$SDK/build-tools/34.0.0/zipalign"
APKSIGNER="$SDK/build-tools/34.0.0/apksigner"

# --- Секреты: только из окружения, в скрипте и логах их нет ---
: "${SMETA_KEYSTORE_PASSWORD:?Не задан SMETA_KEYSTORE_PASSWORD (пароль keystore)}"
: "${KEYSTORE_PATH:?Не задан KEYSTORE_PATH (путь к файлу ключа подписи)}"
KS="$KEYSTORE_PATH"
[ -f "$KS" ] || { echo "ERROR: keystore не найден: $KS" >&2; exit 1; }

VERSION="$(grep -o 'android:versionName="[^"]*"' "$DIR/AndroidManifest.xml" | head -1 | cut -d'"' -f2)"
OUT="$BUILD/SmetaPRO-v$VERSION.apk"

rm -rf "$BUILD"
mkdir -p "$BUILD/compiled" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

"$AAPT2" compile --dir "$DIR/res" -o "$BUILD/compiled.zip"
cd "$BUILD/compiled" && unzip -q -o ../compiled.zip && cd "$DIR"
"$AAPT2" link -o "$BUILD/base.apk" -I "$ANDROID_JAR" --manifest "$DIR/AndroidManifest.xml" --java "$BUILD/gen" "$BUILD/compiled.zip"
find "$BUILD/gen" -name "*.java" > "$BUILD/sources.txt"
find "$DIR/src" -name "*.java" >> "$BUILD/sources.txt"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"
"$D8" --lib "$ANDROID_JAR" --output "$BUILD/dex" $(find "$BUILD/classes" -name "*.class")
cp "$BUILD/base.apk" "$BUILD/app-unsigned.apk"
cd "$BUILD/dex" && zip -q -r ../app-unsigned.apk classes.dex
cd "$DIR" && zip -q -r "$BUILD/app-unsigned.apk" assets/index.html assets/db.js

"$ZIPALIGN" -f 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"
"$APKSIGNER" sign --ks "$KS" --ks-key-alias smetapro \
  --ks-pass "pass:$SMETA_KEYSTORE_PASSWORD" --key-pass "pass:$SMETA_KEYSTORE_PASSWORD" \
  --out "$OUT" "$BUILD/app-aligned.apk"
"$APKSIGNER" verify --verbose --print-certs "$OUT"
"$AAPT2" dump badging "$OUT" | head -3
ls -lh "$OUT"
sha256sum "$OUT"
echo "BUILD OK"
