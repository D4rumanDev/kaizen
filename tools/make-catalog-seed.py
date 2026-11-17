#!/usr/bin/env python3
"""Genera (y firma) catalogo-unificado.json.gz: el catálogo unificado portable para descargar, igual que las fuentes (ver CatalogSeed.kt).

  python tools/make-catalog-seed.py <catalog-snapshot.json.gz> <anilist-links.json.gz> <carpeta-de-salida> [--no-sign] [--publish <carpeta-del-repo-kaizen-sources>]

Entrada: la fotografía del catálogo y la copia de enlaces con AniList que saca `node tools/tv.js backup` (backups/tv-<fecha>/catalog-snapshot.json.gz y anilist-links.json.gz). Salida: <carpeta>/catalogo-unificado.json.gz y
<carpeta>/catalogo-unificado.sig (firma ECDSA P-256 SHA-256 en base64, con la misma clave privada que las fuentes: ~/.android/kaizen-sources-signing.pem o la ruta de
KAIZEN_SOURCES_KEY). Con --no-sign solo se escribe el .gz (para revisarlo; no sirve para publicar: la app rechaza lo que no lleva firma).

El fichero es neutro y portable: agrupa por id de AniList (sin las uniones de nadie) y sin ids locales (los de serie de una instalación no valen en otra) y con su fecha de generación `createdAt` (la app no aplica uno más antiguo que el que ya aplicó). Cada serie lleva nombre, año, nota, votos y sus fichas (fuente + slug); solo
entran las fuentes que existen en app/src/main/assets/sources y las series con alguna ficha. `anilistId` es el anime de AniList de la serie (ausente en las fichas sin emparejar, que van solas). Con --publish <carpeta> copia los dos ficheros al repositorio de fuentes (kaizen-sources, junto a enlaces-anilist.json.gz); el commit y el push allí los haces tú.
"""
import base64, collections, gzip, hashlib, json, os, re, subprocess, sys, time, unicodedata

SLUG = re.compile(r'[A-Za-z0-9._~-]{1,400}')  # igual que CatalogSeed.SLUG: lo que no cumpla lo rechazaría la app entera


def clean_name(name):
    """Sin caracteres de control ni de formato (la app rechaza el fichero si uno solo los lleva); los 0x80-0x9f son cp1252 mal leído y se traducen."""
    fixed = ''.join(bytes([ord(c)]).decode('cp1252', 'ignore') if 0x80 <= ord(c) <= 0x9f else c for c in name)
    return ''.join(c for c in fixed if unicodedata.category(c) not in ('Cc', 'Cf')).strip()

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCES = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'sources')
FILE = 'catalogo-unificado.json.gz'
SIG = 'catalogo-unificado.sig'


def known_sources():
    return {json.load(open(os.path.join(SOURCES, f), encoding='utf-8'))['id'] for f in os.listdir(SOURCES) if f.endswith('.json') and not f.startswith('index')}


def anilist_ids(links_doc, known):
    """(fuente, slug) -> id de AniList, solo de los enlaces automáticos y aplicados (nunca lo que decidió el usuario ni propuestas): así el catálogo no lleva uniones personales."""
    levels = {'seguro', 'probable', 'mal', 'web', 'datos', 'mismo-id'}
    ids = {}
    for r in links_doc['links']:
        if r['origen'] == 'auto' and r['estado'] == 'aplicado' and r.get('anilistId') and r['nivel'] in levels and r['fuente'] in known and SLUG.fullmatch(r['slug']):
            ids[(r['fuente'], r['slug'])] = int(r['anilistId'])
    return ids


def build(snapshot, links_doc, known):
    """El documento portable (sin escribir nada). Las fichas con el mismo id de AniList forman una serie; las demás van solas. Devuelve (documento, fichas omitidas)."""
    entries = {e['key']: e for e in snapshot['entries']}
    ids = anilist_ids(links_doc, known)
    groups = collections.defaultdict(list)
    skipped = 0
    for l in snapshot['links']:
        if l['sourceId'] not in known or not l.get('slug') or not SLUG.fullmatch(l['slug']) or l['slug'] in ('.', '..'):
            skipped += 1
            continue
        anilist = ids.get((l['sourceId'], l['slug']))
        # Lo que no está emparejado va solo: la serie es esa ficha, sin las uniones de nadie.
        groups[('a', anilist) if anilist else ('s', l['sourceId'], l['slug'])].append(l)
    series = []
    for group, links in groups.items():
        names = collections.Counter(clean_name(l.get('name') or (entries.get(l['key']) or {}).get('name') or '') for l in links)
        name = next((n for n, _ in names.most_common() if n), '')
        if not name or len(name.encode('utf-16-le')) // 2 > 300:  # la app mide en UTF-16 y rechaza el fichero entero por un nombre así: se omite la serie
            skipped += len(links)
            continue
        item = {'name': name}
        if group[0] == 'a':
            item['anilistId'] = group[1]
        rated = [entries[l['key']] for l in links if l['key'] in entries]
        years = [e['year'] for e in rated if e.get('year')]
        if years:
            item['year'] = min(years)
        best = max((e for e in rated if e.get('score') is not None), key=lambda e: e.get('votes') or 0, default=None)
        if best:
            item['score'] = round(best['score'], 2)
            if best.get('votes'):
                item['votes'] = best['votes']
        item['fichas'] = [{'fuente': f, 'slug': sl} for f, sl in sorted({(l['sourceId'], l['slug']) for l in links})][:30]
        series.append(item)
    series.sort(key=lambda s: (s['name'].lower(), s['fichas'][0]['fuente'], s['fichas'][0]['slug']))
    return {'version': 2, 'createdAt': int(time.time() * 1000), 'series': series}, skipped


def main():
    argv = sys.argv[1:]
    publish = None
    if '--publish' in argv:
        i = argv.index('--publish')
        publish = argv[i + 1] if i + 1 < len(argv) else sys.exit('--publish necesita la carpeta del repositorio de fuentes')
        del argv[i:i + 2]
    args = [a for a in argv if not a.startswith('--')]
    if len(args) != 3:
        sys.exit(__doc__)
    src, links_src, out = args
    snapshot = json.load(gzip.open(src, 'rt', encoding='utf-8'))
    links_doc = json.load(gzip.open(links_src, 'rt', encoding='utf-8'))
    if snapshot.get('version') != 1:
        sys.exit(f"Versión de fotografía no soportada: {snapshot.get('version')}")
    doc, skipped = build(snapshot, links_doc, known_sources())
    os.makedirs(out, exist_ok=True)
    gz = os.path.join(out, FILE)
    with gzip.GzipFile(gz, 'wb', compresslevel=9, mtime=0) as g:
        g.write(json.dumps(doc, ensure_ascii=False, separators=(',', ':')).encode('utf-8'))
    fichas = sum(len(s['fichas']) for s in doc['series'])
    per_source = collections.Counter(f['fuente'] for s in doc['series'] for f in s['fichas'])
    print(f"{len(doc['series'])} series, {fichas} fichas (omitidas {skipped}), {sum(1 for x in doc['series'] if 'anilistId' in x)} con id de AniList, {os.path.getsize(gz) // 1024} KB, sha256 {hashlib.sha256(open(gz, 'rb').read()).hexdigest()[:16]}:", dict(per_source))
    if '--no-sign' in sys.argv:
        print('Sin firmar (--no-sign): solo para revisar, la app no lo usará.')
        return
    key = os.environ.get('KAIZEN_SOURCES_KEY', os.path.expanduser('~/.android/kaizen-sources-signing.pem'))
    if not os.path.isfile(key):
        sys.exit(f'No encuentro la clave de firma: {key} (usa --no-sign para generar solo el fichero)')
    sig = subprocess.run(['openssl', 'dgst', '-sha256', '-sign', key, gz], check=True, capture_output=True).stdout
    open(os.path.join(out, SIG), 'wb').write(base64.b64encode(sig))
    print(f'Firmado: {os.path.join(out, SIG)}')
    if publish:
        import shutil
        for name in (FILE, SIG):
            shutil.copy2(os.path.join(out, name), os.path.join(publish, name))
        print(f'Copiado a {publish} (haz commit y push allí; antes revisa que la fotografía no lleve uniones o separaciones personales)')


if __name__ == '__main__':
    main()
