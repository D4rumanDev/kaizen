"""Claves que una web con datos en JavaScript (format jsData, p. ej. animeav1) envía de verdad en la ficha de una serie.

Uso (desde la raiz del repo, solo lectura; no toca la app ni la TV):
    python tools/audit/ficha_keys.py URL_O_FICHERO [MARCA]

Descarga la pagina (o lee un fichero HTML ya bajado), busca el objeto de datos que contiene MARCA (por defecto «synopsis») y
lista sus claves de primer nivel con el principio de su valor. Los textos largos no se imprimen enteros. Sirve para comparar
lo que la web envia con lo que extrae la especificacion de la fuente (app/src/main/assets/sources/*.json, bloque «anime»).
"""
import re
import sys
import urllib.request

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
BS = chr(92)  # la barra invertida, sin escribirla (la consola y los heredoc se la comen)


def load(source):
    if source.startswith("http"):
        request = urllib.request.Request(source, headers={"User-Agent": UA})
        return urllib.request.urlopen(request, timeout=25).read().decode("utf8", "replace")
    return open(source, encoding="utf8").read()


def enclosing(text, at):
    """(inicio, fin) del objeto o lista que contiene la posicion `at`."""
    depth = 0
    start = at
    while start > 0:
        start -= 1
        if text[start] in "}]":
            depth += 1
        elif text[start] in "{[":
            if depth == 0:
                break
            depth -= 1
    depth = 0
    quote = None
    end = start
    while end < len(text):
        c = text[end]
        if quote:
            if c == BS:
                end += 1
            elif c == quote:
                quote = None
        elif c in "\"'`":
            quote = c
        elif c in "{[":
            depth += 1
        elif c in "}]":
            depth -= 1
            if depth == 0:
                break
        end += 1
    return start, end


def top_level_keys(obj):
    keys = []
    depth = 0
    quote = None
    skip = False
    for pos, c in enumerate(obj):
        if skip:
            skip = False
            continue
        if quote:
            if c == BS:
                skip = True
            elif c == quote:
                quote = None
            continue
        if c in "\"'`":
            quote = c
        elif c in "{[":
            depth += 1
        elif c in "}]":
            depth -= 1
        elif depth == 1 and c == ":":
            found = re.search(r"([A-Za-z_]\w*)\s*$", obj[:pos])
            if found:
                keys.append((found.group(1), pos))
    return keys


def fields(obj):
    """Las claves de primer nivel de `obj` con su valor tal cual viene escrito (texto), en orden."""
    keys = top_level_keys(obj)
    out = []
    for index, (name, pos) in enumerate(keys):
        # El valor llega hasta donde empieza la clave siguiente (menos su coma) o hasta el cierre del objeto.
        following = keys[index + 1][1] - len(keys[index + 1][0]) if index + 1 < len(keys) else len(obj) - 1
        out.append((name, re.sub(r"\s+", " ", obj[pos + 1:following]).rstrip(", ")))
    return out


def media_of(text, mark="synopsis"):
    """El objeto de la serie dentro de la pagina (el que contiene `mark`), o None."""
    at = text.find(mark)
    if at < 0:
        return None
    start, end = enclosing(text, at)
    return text[start:end + 1]


def main():
    # La consola de Windows (cp1252) no imprime japones ni emojis: sin esto, un titulo original rompe el listado.
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    text = load(sys.argv[1])
    mark = sys.argv[2] if len(sys.argv) > 2 else "synopsis"
    at = text.find(mark)
    if at < 0:
        print(f"No aparece «{mark}» en la pagina ({len(text)} caracteres).")
        return 1
    start, end = enclosing(text, at)
    obj = text[start:end + 1]
    print(f"Objeto con «{mark}»: {len(obj)} caracteres")
    for name, value in fields(obj):
        shown = f"<texto de {len(value)} caracteres>" if name in ("synopsis", "description") else value[:110]
        print(f"{name:20} {shown}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
