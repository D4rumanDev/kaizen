#!/usr/bin/env bash
# Compila (Gradle firma con el keystore de KEYSTORE_DIR), verifica e instala KAIZEN (variante tv), comprueba que arranca y lo compila en modo speed.
# Uso: tools/build-kaizen.sh [IP:puerto ...]   (por defecto ADB_DEVICE de tools/adb-device/.env)
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot}"
SDK_DIR="${ANDROID_SDK_DIR:-$LOCALAPPDATA/Android/Sdk}"
BUILD_TOOLS="$SDK_DIR/build-tools/36.0.0"
# Debe coincidir con KAIZEN_KEYSTORE_DIR de local.properties (Gradle usa ~/.android por defecto).
KEYSTORE_DIR="${KAIZEN_KEYSTORE_DIR:-$HOME/.android}"
KEYSTORE="$KEYSTORE_DIR/kaizen-tvlibre.jks"
KEYSTORE_PASS_FILE="$KEYSTORE_DIR/kaizen-tvlibre.pass"
PACKAGE="app.kaizen"
OUT_APK="$REPO_DIR/../kaizen.apk"
[ -f "$REPO_DIR/tools/adb-device/.env" ] && source "$REPO_DIR/tools/adb-device/.env"
DEVICES=("${@:-${ADB_DEVICE:?Define ADB_DEVICE en tools/adb-device/.env o pasa IP:puerto}}")

export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH" MSYS_NO_PATHCONV=1

fail() { echo "ERROR: $*" >&2; exit 1; }
# Con MSYS_NO_PATHCONV=1 las herramientas nativas de Windows necesitan rutas Windows.
win() { cygpath -m "$1"; }

[ -f "$KEYSTORE" ] || fail "no existe el keystore $KEYSTORE (copia en Vaultwarden)"
[ -f "$KEYSTORE_PASS_FILE" ] || fail "no existe el fichero de contraseña $KEYSTORE_PASS_FILE"

SIGNED="$REPO_DIR/app/build/outputs/apk/tv/app-tv.apk"
# Sin keystore Gradle no firma: que no quede el APK firmado de una compilación anterior.
rm -f "$SIGNED"

echo "1/5 Compilando assembleTv..."
(cd "$REPO_DIR" && ./gradlew --no-daemon -q :app:assembleTv) || fail "falló la compilación"

echo "2/5 Comprobando la firma (la pone Gradle, alias origami)..."
[ -f "$SIGNED" ] || fail "Gradle no generó $SIGNED firmado"
cp "$SIGNED" "$OUT_APK"
# apksigner.bat (cmd) necesita JAVA_HOME en formato Windows.
JAVA_HOME="$(win "$JAVA_HOME")" "$BUILD_TOOLS/apksigner.bat" verify "$(win "$OUT_APK")" || fail "firma no válida"
echo "   APK: $OUT_APK ($(du -h "$OUT_APK" | cut -f1))"

for device in "${DEVICES[@]}"; do
  echo "3/5 Instalando en $device..."
  adb connect "$device" >/dev/null
  # -f: en la memoria interna. Sin él, al reinstalar el sistema la deja en el volumen ampliado, que escribe unas 2,5 veces más lento y dejó la app sin responder (ANR).
  adb -s "$device" install -r -f "$(win "$OUT_APK")" | tail -1 || fail "falló la instalación en $device"

  echo "4/5 Comprobando arranque en $device..."
  adb -s "$device" shell "logcat -c; am force-stop $PACKAGE; \
    monkey -p $PACKAGE -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1; sleep 8"
  pid=$(adb -s "$device" shell pidof "$PACKAGE" | tr -d '\r' || true)
  crashes=$(adb -s "$device" shell "logcat -d | grep -cE 'FATAL EXCEPTION' || true" | tr -d '\r')
  [ -n "$pid" ] && [ "${crashes:-0}" = "0" ] || fail "Kaizen no arranca bien en $device (pid='$pid', crashes=$crashes)"
  echo "   OK en $device (pid $pid)"

  # Compilación completa (AOT) en el dispositivo: medido en el tvbox, el arranque pasa de ~845 a ~595 ms y los frames lentos
  # del 18 % al 9 % frente al modo por perfil con el que instala el sistema. Dura ~1 min y se pierde al reinstalar.
  # Opcional (KAIZEN_COMPILE_SPEED=1): satura el disco del tvbox durante un par de minutos y la app puede dejar de responder (ANR) si se usa
  # entonces. Mejor lanzarlo con la tele sin usar.
  if [ "${KAIZEN_COMPILE_SPEED:-0}" = "1" ]; then
    echo "5/5 Compilando en modo speed en $device..."
    adb -s "$device" shell "cmd package compile -f -m speed $PACKAGE" | tail -1 || echo "   (no se pudo compilar en modo speed; la app funciona igual)"
  else
    echo "5/5 Sin compilar en modo speed (KAIZEN_COMPILE_SPEED=1 para hacerlo)."
  fi
done
