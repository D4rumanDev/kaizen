#!/usr/bin/env python3
"""Recursos de app/src/main/res que no usa nada (restos de la app de móvil de origen).

  python tools/audit/unused_resources.py            # informe
  python tools/audit/unused_resources.py --apply    # borra los valores sin uso de los XML de `values*` (colores, dimensiones, enteros, booleanos, cadenas, listas)

Un recurso cuenta como usado si aparece como `R.tipo.nombre` o `@tipo/nombre` en el código (main y pruebas) o en cualquier XML (manifiesto, layouts, estilos, otros valores). Los puntos del
nombre se escriben con `_` en `R`. No sabe de `getIdentifier` (no hay ninguno en el proyecto). Ficheros de `drawable`, `layout`, `mipmap`, `anim` y `xml` solo se informan: se borran a mano
(un layout puede usarse por su clase de ViewBinding, que no lleva `R.layout`). `shrinkResources` ya quita los sin uso del APK; esto limpia el código fuente.
"""
import collections, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res')
KINDS = {'string': 'string', 'color': 'color', 'dimen': 'dimen', 'integer': 'integer', 'bool': 'bool', 'string-array': 'array', 'array': 'array', 'integer-array': 'array', 'plurals': 'plurals'}
NL = chr(10)


def values_files():
    for d, _, fs in os.walk(RES):
        if os.path.basename(d).startswith('values'):
            for f in fs:
                if f.endswith('.xml'):
                    yield os.path.join(d, f)


def corpus():
    text = ''
    for base in (os.path.join(ROOT, 'app', 'src', 'main'), os.path.join(ROOT, 'app', 'src', 'test')):
        for d, _, fs in os.walk(base):
            if os.sep + 'assets' in d:
                continue
            for f in fs:
                if f.endswith(('.kt', '.java', '.xml')):
                    text += open(os.path.join(d, f), encoding='utf-8', errors='ignore').read() + NL
    return text


def main():
    apply = '--apply' in sys.argv
    text = corpus()
    defs = []  # (fichero, tipo, nombre)
    for p in values_files():
        t = open(p, encoding='utf-8', errors='ignore').read()
        for tag, name in re.findall(r'<(string|color|dimen|integer|bool|string-array|array|integer-array|plurals)\s+name="([^"]+)"', t):
            defs.append((p, tag, name))
    unused = collections.defaultdict(list)
    for p, tag, name in defs:
        kind = KINDS[tag]
        r_name = name.replace('.', '_')
        pattern = r'(R\.%s\.%s(?![\w])|@%s/%s(?![\w.]))' % (kind, re.escape(r_name), kind, re.escape(name))
        if not re.search(pattern, text):
            unused[p].append((tag, name))
    total = sum(len(v) for v in unused.values())
    by_tag = collections.Counter(tag for v in unused.values() for tag, _ in v)
    print(f'valores sin uso: {total}  {dict(by_tag)}')
    for p, items in sorted(unused.items()):
        print(f'  {os.path.relpath(p, ROOT)}: ' + ', '.join(f'{n}' for _, n in items[:12]) + (' …' if len(items) > 12 else ''))
    for sub in ('drawable', 'layout', 'mipmap', 'anim', 'xml'):
        names = set()
        for d, _, fs in os.walk(RES):
            if os.path.basename(d).startswith(sub):
                names |= {os.path.splitext(f)[0] for f in fs}
        un = sorted(n for n in names if not re.search(r'(R\.%s\.%s(?![\w])|@%s/%s(?![\w]))' % (sub, re.escape(n), sub, re.escape(n)), text))
        if un:
            print(f'ficheros de {sub} sin referencia: {un}')
    if not apply:
        print('(informe: --apply borra los valores sin uso)')
        return
    for p, items in unused.items():
        raw = open(p, encoding='utf-8', newline='').read()
        for tag, name in items:
            raw = re.sub(r'[ \t]*<%s\s+name="%s"[^>]*?/>[ \t]*\r?\n?' % (tag, re.escape(name)), '', raw)
            raw = re.sub(r'[ \t]*<%s\s+name="%s"[^>]*>.*?</%s>[ \t]*\r?\n?' % (tag, re.escape(name), tag), '', raw, flags=re.S)
        left = re.sub(r'<\?xml[^>]*\?>|</?resources[^>]*>|<!--.*?-->', '', raw, flags=re.S).strip()
        if not left:
            os.remove(p)
            print('borrado el fichero vacío', os.path.relpath(p, ROOT))
        else:
            open(p, 'w', encoding='utf-8', newline='').write(raw)
    print('borrados', total, 'valores')


main()
