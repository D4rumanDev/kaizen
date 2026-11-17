#!/usr/bin/env python3
"""
Edición por lotes de ficheros de texto conservando el final de línea de cada uno (LF o CRLF) y en UTF-8, con comprobaciones: cada texto a
sustituir debe aparecer **exactamente una vez** (o las que diga `count`); si algo no cuadra no se escribe ningún fichero.

Es lo que antes se rehacía con un script suelto por cambio (en Temp): `pendientes.md` y varios .md son CRLF, y un `sed` o un `Edit` con saltos de línea
se equivocan con ellos.

Uso (desde la raíz del repo):
    python tools/edit.py cambios.json          (el JSON puede estar en cualquier sitio)
    python tools/edit.py - < cambios.json      (por la entrada estándar)
    python tools/edit.py cambios.json --dry-run

Formato del JSON:
    [
      {"file": "ruta/relativa.md",
       "pairs": [["texto viejo", "texto nuevo"], ["otro", "otro nuevo", 2]]},      <- el tercer valor es cuántas veces debe aparecer (1 por defecto)
       {"file": "otra.kt", "append": "\\ntexto al final"}                          <- opcional: añadir al final (se quita el salto final y se pone uno)
    ]
Los saltos de línea del JSON se escriben como \\n; el fichero se escribe con los suyos (\\r\\n si los tenía).
"""
import json
import os
import sys


def edit(spec, dry_run=False):
    pending = []
    for item in spec:
        path = item["file"]
        raw = open(path, "rb").read().decode("utf-8")
        crlf = "\r\n" in raw
        if crlf and raw.replace("\r\n", "").count("\n"):
            sys.exit(f"{path}: mezcla LF y CRLF; no se toca (normalizarlo exigiría reescribir líneas ajenas)")
        text = raw.replace("\r\n", "\n")
        for pair in item.get("pairs", []):
            old, new = pair[0], pair[1]
            expected = pair[2] if len(pair) > 2 else 1
            found = text.count(old)
            if found != expected:
                raise SystemExit(f"{path}: «{old[:60]}» aparece {found} veces, se esperaban {expected}; no se escribe nada")
            text = text.replace(old, new)
        if "append" in item:
            text = text.rstrip("\n") + "\n" + item["append"].lstrip("\n")
            if not text.endswith("\n"):
                text += "\n"
        pending.append((path, text.replace("\n", "\r\n") if crlf else text, crlf))
    for path, content, crlf in pending:
        print(f"{'(simulacro) ' if dry_run else ''}{path} ({'CRLF' if crlf else 'LF'})")
        if not dry_run:
            tmp = path + ".tmp"
            open(tmp, "wb").write(content.encode("utf-8"))
            os.replace(tmp, path)


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "--dry-run"]
    if not args:
        raise SystemExit(__doc__)
    source = sys.stdin.read() if args[0] == "-" else open(args[0], encoding="utf-8").read()
    edit(json.loads(source), dry_run="--dry-run" in sys.argv)
