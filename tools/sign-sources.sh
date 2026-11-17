#!/usr/bin/env bash
# Regenera el índice firmado de las fuentes por fichero (app/src/main/assets/sources/).
#
#   tools/sign-sources.sh            # genera index.json e index.json.sig
#   tools/sign-sources.sh --publish <carpeta-del-repo-de-fuentes>   # y copia los ficheros allí
#
# La clave privada (ECDSA P-256, PEM) NO está en el repositorio: por defecto ~/.android/kaizen-sources-signing.pem,
# o la ruta de KAIZEN_SOURCES_KEY. La pública va en SpecSources.PUBLIC_KEY. Cada fuente lleva su "version"
# dentro de su JSON: súbela cuando la cambies, porque la app nunca baja de versión.
set -euo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/sources"
KEY="${KAIZEN_SOURCES_KEY:-$HOME/.android/kaizen-sources-signing.pem}"
[ -f "$KEY" ] || { echo "No encuentro la clave de firma: $KEY" >&2; exit 1; }

python3 - "$DIR" <<'PY'
import hashlib, json, os, sys
d = sys.argv[1]
entries = []
for name in sorted(os.listdir(d)):
    if not name.endswith(".json") or name.startswith("index"):
        continue
    data = open(os.path.join(d, name), "rb").read()
    spec = json.loads(data)
    entries.append({"id": spec["id"], "file": name, "version": spec["version"], "sha256": hashlib.sha256(data).hexdigest()})
index = {"schema": 1, "version": max([e["version"] for e in entries] or [0]), "sources": entries}
open(os.path.join(d, "index.json"), "w", encoding="utf-8", newline="\n").write(json.dumps(index, indent=2) + "\n")
print(f"{len(entries)} fuentes en el índice")
PY

openssl dgst -sha256 -sign "$KEY" "$DIR/index.json" | base64 -w0 > "$DIR/index.json.sig"
# Comprobación con la clave pública derivada de la privada.
openssl ec -in "$KEY" -pubout -out "$DIR/.pub.pem" 2>/dev/null
base64 -d "$DIR/index.json.sig" > "$DIR/.sig.bin"
openssl dgst -sha256 -verify "$DIR/.pub.pem" -signature "$DIR/.sig.bin" "$DIR/index.json" >/dev/null
rm -f "$DIR/.pub.pem" "$DIR/.sig.bin"
echo "Índice firmado y verificado: $DIR/index.json(.sig)"

if [ "${1:-}" = "--publish" ]; then
  DEST="${2:?falta la carpeta de destino}"
  cp "$DIR"/*.json "$DIR"/index.json.sig "$DEST"/
  echo "Copiado a $DEST (haz commit y push allí)"
fi
