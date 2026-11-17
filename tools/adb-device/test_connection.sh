#!/usr/bin/env bash
# Verifica disponibilidad de adb y conectividad con el dispositivo TV
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/.env" ]; then
  # shellcheck disable=SC1091
  source "$SCRIPT_DIR/.env"
fi

DEVICE="${ADB_DEVICE:?Define ADB_DEVICE en tools/adb-device/.env (ver .env.example)}"
PACKAGE="${ADB_PACKAGE:-app.kaizen}"

echo "[tools/adb-device] Comprobando binario adb..."
if ! command -v adb >/dev/null 2>&1; then
  echo "WARN: 'adb' no está en el PATH del sistema actual."
  exit 1
fi

echo "[tools/adb-device] Intentando conectar con $DEVICE..."
adb connect "$DEVICE" || true

echo "[tools/adb-device] Dispositivos conectados:"
adb devices -l

if adb devices | grep -q "$DEVICE"; then
  echo "OK: Conexión establecida con $DEVICE."
  echo "[tools/adb-device] Comprobando estado de $PACKAGE..."
  pid=$(adb -s "$DEVICE" shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)
  if [ -n "$pid" ]; then
    echo "OK: $PACKAGE está en ejecución (PID: $pid)."
  else
    echo "INFO: $PACKAGE no está en ejecución actualmente en $DEVICE."
  fi
else
  echo "WARN: No se pudo verificar la conexión con $DEVICE."
  exit 1
fi

