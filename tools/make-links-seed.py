#!/usr/bin/env python3
"""Genera y firma enlaces-anilist.json.gz (enlaces de partida de una instalación limpia, ver AnilistSeed.kt).

  python tools/make-links-seed.py <anilist-links.json.gz> <carpeta-de-salida> [--matcher N]

Entrada: la copia de enlaces que saca BACKUP de la TV (backups/tv/anilist-links.json.gz). Salida: <carpeta>/enlaces-anilist.json.gz y
<carpeta>/enlaces-anilist.sig (firma ECDSA P-256 SHA-256 en base64, con la misma clave privada que las fuentes: ~/.android/kaizen-sources-signing.pem
o la ruta de KAIZEN_SOURCES_KEY). Solo entran los enlaces automáticos y aplicados (nunca lo del usuario ni propuestas). --matcher: la versión de
AnilistMatcher.VERSION con la que se hicieron (por defecto la lee del código). Después se comprueba la firma con SpecSources.PUBLIC_KEY.
"""
import base64, collections, gzip, json, os, re, subprocess, sys, tempfile, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCES = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'sources')
LEVELS = {'seguro', 'probable', 'mal', 'web', 'datos', 'mismo-id'}


def matcher_version():
    text = open(os.path.join(ROOT, 'app', 'src', 'main', 'java', 'app', 'kaizen', 'source', 'AnilistMatcher.kt'), encoding='utf-8').read()
    return int(re.search(r'const val VERSION = (\d+)', text).group(1))


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    if len(args) != 2:
        sys.exit(__doc__)
    src, out = args
    version = int(sys.argv[sys.argv.index('--matcher') + 1]) if '--matcher' in sys.argv else matcher_version()
    key = os.environ.get('KAIZEN_SOURCES_KEY', os.path.expanduser('~/.android/kaizen-sources-signing.pem'))
    if not os.path.isfile(key):
        sys.exit(f'No encuentro la clave de firma: {key}')
    data = json.load(gzip.open(src, 'rt', encoding='utf-8'))
    known = {json.load(open(os.path.join(SOURCES, f), encoding='utf-8'))['id'] for f in os.listdir(SOURCES) if f.endswith('.json') and not f.startswith('index')}
    links, skipped = [], 0
    for r in data['links']:
        if r['origen'] != 'auto' or r['estado'] != 'aplicado' or not r.get('anilistId') or r['nivel'] not in LEVELS or r['fuente'] not in known:
            skipped += 1
            continue
        links.append({'fuente': r['fuente'], 'slug': r['slug'], 'anilistId': r['anilistId'], 'nivel': r['nivel'], 'puntuacion': round(r.get('puntuacion') or 1.0, 2)})
    links.sort(key=lambda x: (x['fuente'], x['slug']))
    doc = {'version': 1, 'matcherVersion': version, 'createdAt': time.strftime('%Y-%m-%d', time.gmtime((data.get('createdAt') or time.time() * 1000) / 1000)), 'links': links}
    os.makedirs(out, exist_ok=True)
    gz = os.path.join(out, 'enlaces-anilist.json.gz')
    with gzip.GzipFile(gz, 'wb', compresslevel=9, mtime=0) as g:
        g.write(json.dumps(doc, ensure_ascii=False, separators=(',', ':')).encode('utf-8'))
    sig = subprocess.run(['openssl', 'dgst', '-sha256', '-sign', key, gz], check=True, capture_output=True).stdout
    open(os.path.join(out, 'enlaces-anilist.sig'), 'wb').write(base64.b64encode(sig))
    print(f'{len(links)} enlaces (omitidos {skipped}), criterio v{version}:', dict(collections.Counter(x['fuente'] for x in links)))


main()
