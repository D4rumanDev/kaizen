#!/usr/bin/env bash
# Vista previa de lo que obtiene el sistema de scrap de una ficha, sin instalar nada ni tocar bases de datos.
#
#   tools/ficha-preview.sh URL_DE_LA_FICHA      # p. ej. https://animeav1.com/media/black-clover
#
# Baja la página (una sola petición) y la pasa por el código real de la app (JsExtractor en las webs de datos JSON, la especificación de la fuente y
# `readFicha`) con una prueba de la JVM (FichaPreviewTest). Imprime el registro único que se guardaría en WebFicha (todas las claves; ver
# docs/wiki/webficha-esquema.md), sus columnas indexadas y la serie que usa la app. Vale para todas las fuentes. Tarda ~1 min (compila y arranca Gradle).
set -euo pipefail
cd "$(dirname "$0")/.."
URL="${1:?falta la URL de la ficha}"
OUT="app/build/probe/ficha-preview.txt"
rm -f "$OUT"
# cleanTest fuerza a repetir la prueba: Gradle no mira la variable de entorno y la daría por hecha.
KAIZEN_FICHA_URL="$URL" ./gradlew --no-daemon -q :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests 'app.kaizen.source.FichaPreviewTest' >/tmp/ficha-preview.log 2>&1 \
  || { tail -30 /tmp/ficha-preview.log; echo "ficha-preview: falló la prueba" >&2; exit 1; }
[ -f "$OUT" ] || { echo "ficha-preview: la prueba no escribió $OUT (¿URL de una fuente HTML?)" >&2; exit 1; }
cat "$OUT"
