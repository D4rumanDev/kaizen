"""Mide si buscar en AniList con el formato del slug («movie», «ova», «especiales») encuentra la entrada propia de una ficha de película, OVA o especial.

Uso (desde la raíz del repo; los ficheros salen de la TV con `adb pull .../files/backups/anilist-links.json.gz` y `node tools/tv.js export-fichas`):
    python tools/audit/format_search.py ruta/anilist-links.json.gz ruta/fichas-anilist-FECHA.jsonl.gz [--limit 120] [--cache format-search.json]

Toma las fichas con enlace automático cuyo slug dice película, OVA o especial y cuyo anime enlazado es una serie de TV (las que el criterio de formato deja sin enlace) y, para cada
una, pregunta a AniList (lectura pública, 1,2 s entre peticiones, guardado en --cache) por el título sin la palabra de formato **filtrando por formato**: película, OVA/ONA/especial.
Con el parecido de los nombres (el mismo criterio que `pending.py`) cuenta cuántas tendrían una entrada propia «segura» (≥ 0,97), «probable» (≥ 0,85) o ninguna.
No escribe nada en la app ni en la TV.
"""
import argparse
import base64
import collections
import gzip
import json
import os
import re
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(__file__))
import pending as P  # noqa: E402

QUERY = 'query($q:String,$f:[MediaFormat]){Page(page:1,perPage:8){media(search:$q,type:ANIME,format_in:$f,sort:SEARCH_MATCH){id format episodes seasonYear title{romaji english native} synonyms}}}'
MARK = {'movie': 'MOVIE', 'pelicula': 'MOVIE', 'peliculas': 'MOVIE', 'film': 'MOVIE', 'ova': 'OVA', 'oav': 'OVA', 'special': 'SPECIAL', 'specials': 'SPECIAL', 'especial': 'SPECIAL', 'especiales': 'SPECIAL'}
FORMATS = {'MOVIE': ['MOVIE'], 'OVA': ['OVA', 'ONA', 'SPECIAL'], 'SPECIAL': ['SPECIAL', 'OVA']}
NOISE = {'latino', 'castellano', 'sub', 'espanol', 'online', 'hd'}


def marker(slug):
    words = [w for w in P.norm(slug.replace('-', ' ')).split() if w not in NOISE]
    for w in words:
        if w in MARK:
            return MARK[w], ' '.join(x for x in words if x not in MARK)
    return None, ' '.join(words)


CACHE_FILE = ['format-search.json']


def ask(text, formats, cache):
    key = f'{text}|{",".join(formats)}'
    if key in cache:
        return cache[key]
    body = json.dumps({'query': QUERY, 'variables': {'q': text, 'f': formats}}).encode()
    for attempt in range(4):
        try:
            request = urllib.request.Request('https://graphql.anilist.co', body, {'Content-Type': 'application/json', 'Accept': 'application/json', 'User-Agent': 'kaizen-tools/1.0'})
            data = json.load(urllib.request.urlopen(request, timeout=30))
            break
        except Exception as error:
            print('reintento', text, error, file=sys.stderr)
            time.sleep(10)
    else:
        raise SystemExit('AniList no responde')
    cache[key] = data['data']['Page']['media']
    if len(cache) % 10 == 0:
        json.dump(cache, open(CACHE_FILE[0], 'w', encoding='utf-8'), ensure_ascii=False)
    time.sleep(1.2)
    return cache[key]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('links')
    ap.add_argument('fichas')
    ap.add_argument('--limit', type=int, default=120)
    ap.add_argument('--cache', default='format-search.json')
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding='utf-8')
    links = json.load(gzip.open(args.links, 'rt', encoding='utf-8'))['links']
    tv = {}
    for line in gzip.open(args.fichas, 'rt', encoding='utf-8'):
        o = json.loads(line)
        if 'anilistId' in o and 'json' in o:
            try:
                tv[o['anilistId']] = json.loads(gzip.decompress(base64.b64decode(o['json'])).decode('utf-8'))
            except Exception:
                pass
    todo = []
    for r in links:
        if r['origen'] != 'auto' or r['estado'] not in ('aplicado', 'pendiente') or r.get('anilistId') not in tv:
            continue
        kind, text = marker(r['slug'])
        m = tv[r['anilistId']]
        if kind and m.get('format') in ('TV', 'TV_SHORT') and text:
            todo.append((r['fuente'], r['slug'], kind, text))
    todo = todo[:args.limit]
    CACHE_FILE[0] = args.cache
    cache = json.load(open(args.cache, encoding='utf-8')) if os.path.exists(args.cache) else {}
    result = collections.Counter()
    samples = collections.defaultdict(list)
    for fuente, slug, kind, text in todo:
        found = ask(text, FORMATS[kind], cache)
        want = P.base_words(P.norm(text).split())
        best = (0.0, None)
        for m in found:
            names = [m['title'].get(k) for k in ('romaji', 'english', 'native')] + list(m.get('synonyms') or [])
            s = max((P.similarity(want, P.base_words([w for w in P.norm(n).split() if w not in MARK])) for n in names if n), default=0.0)
            if s > best[0]:
                best = (s, m)
        s, m = best
        level = 'propia segura' if s >= 0.97 else 'propia probable' if s >= 0.85 else 'hay entradas, ninguna se parece' if found else 'sin entradas de ese formato'
        result[level] += 1
        samples[level].append((fuente, slug, kind, round(s, 2), m and m['title']['romaji'], m and m['format']))
    json.dump(cache, open(args.cache, 'w', encoding='utf-8'), ensure_ascii=False)
    print(f'{len(todo)} fichas de película/OVA/especial enlazadas a una serie de TV')
    for level, n in result.most_common():
        print(f'{n:5d}  {level}')
    for level, items in samples.items():
        print(f'\n== {level}')
        for item in items[:8]:
            print('  ', *item)


if __name__ == '__main__':
    main()
