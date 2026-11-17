"""Mira por qué quedan fichas «por revisar» en el emparejado con AniList y cuántas se podrían resolver con datos que ya hay.

Uso (desde la raíz del repo; la copia sale de la TV con `adb pull .../files/backups/anilist-links.json.gz`):
    python tools/audit/audit.py pending ruta/anilist-links.json.gz [--cache pending-anilist.json] [--sample 15]

Pide a AniList (lectura pública, 50 animes por petición) los títulos de los candidatos pendientes y los guarda en --cache para no repetirlo.
Con ellos clasifica cada ficha pendiente según el slug de la ficha frente a los nombres del anime candidato:
  - «temporada»: el parecido es alto salvo por el número de temporada (la ficha es de otra temporada que el candidato);
  - «titulo»: la temporada coincide y el título se parece, pero el parecido no llega a probable;
  - «otro»: ni una cosa ni otra.
No escribe nada en la app ni en la TV.
"""
import argparse
import collections
import gzip
import json
import os
import re
import sys
import time
import unicodedata
import urllib.request

QUERY = 'query($ids:[Int]){Page(page:1,perPage:50){media(id_in:$ids,type:ANIME){id format seasonYear episodes title{romaji english native} synonyms}}}'

ORDINALS = {'second': 2, '2nd': 2, 'third': 3, '3rd': 3, 'fourth': 4, '4th': 4, 'fifth': 5, '5th': 5, 'ii': 2, 'iii': 3, 'iv': 4}
NOISE = {'tv', 'the', 'no', 'wa', 'ga', 'to', 'wo', 'ni', 'de', 'ka', 'mo', 'season', 'temporada', 'part', 'parte', 'cour', 'latino', 'castellano', 'sub', 'espanol'}


def norm(text):
    text = unicodedata.normalize('NFD', text or '')
    text = ''.join(c for c in text if unicodedata.category(c) != 'Mn').lower()
    return re.sub(r'[^a-z0-9]+', ' ', text).strip()


def season_of(words):
    for i, w in enumerate(words):
        if w in ('season', 'temporada') and i + 1 < len(words) and words[i + 1].isdigit():
            return int(words[i + 1])
        if w in ORDINALS and (i + 1 < len(words) and words[i + 1] in ('season', 'temporada') or w in ('ii', 'iii', 'iv')):
            return ORDINALS[w]
        m = re.fullmatch(r'(\d)(st|nd|rd|th|ra|da|nda|era|er|ta|to|vo|mo|ma|va|na)', w)
        if m:
            return int(m.group(1))
        m = re.fullmatch(r's(\d)', w)
        if m:
            return int(m.group(1))
    if len(words) > 1 and len(words[-1]) == 1 and words[-1] in '23456789':
        return int(words[-1])
    return 1


def base_words(words):
    keep = []
    for i, w in enumerate(words):
        if w in NOISE or w in ORDINALS or re.fullmatch(r'(\d)(st|nd|rd|th|ra|da|nda|era|er|ta|to|vo|mo|ma|va|na)', w) or re.fullmatch(r's\d', w):
            continue
        if w.isdigit() and i > 0 and words[i - 1] in ('season', 'temporada'):
            continue
        keep.append(w)
    if len(keep) > 1 and len(keep[-1]) == 1 and keep[-1] in '23456789':
        keep.pop()
    return set(keep)


def similarity(a, b):
    return len(a & b) / len(a | b) if a and b else 0.0


def fold(word):
    return word.replace('ou', 'o').replace('oo', 'o').replace('uu', 'u')


def dice(a, b):
    """Parecido de dos textos por sus pares de letras seguidas (0 a 1)."""
    def pairs(x):
        out = collections.Counter(x[i:i + 2] for i in range(len(x) - 1))
        return out
    x, y = pairs(a), pairs(b)
    if not x or not y:
        return 1.0 if a == b else 0.0
    return 2.0 * sum(min(v, y.get(k, 0)) for k, v in x.items()) / (sum(x.values()) + sum(y.values()))


def compact(words):
    """Las palabras base plegadas y pegadas, sin orden: «shitemo» y «shite mo» quedan iguales."""
    return ''.join(sorted(fold(w) for w in base_words(words)))


def fetch(ids, cache_file):
    cache = {}
    if os.path.exists(cache_file):
        cache = {int(k): v for k, v in json.load(open(cache_file, encoding='utf-8')).items()}
    missing = [i for i in ids if i not in cache]
    for start in range(0, len(missing), 50):
        body = json.dumps({'query': QUERY, 'variables': {'ids': missing[start:start + 50]}}).encode()
        for attempt in range(4):
            try:
                request = urllib.request.Request('https://graphql.anilist.co', body, {'Content-Type': 'application/json', 'Accept': 'application/json', 'User-Agent': 'kaizen-tools/1.0'})
                data = json.load(urllib.request.urlopen(request, timeout=30))
                break
            except Exception as error:  # límite de peticiones o red: se espera y se reintenta
                print('reintento', start, error, file=sys.stderr)
                time.sleep(10)
        else:
            raise SystemExit('AniList no responde')
        for media in data['data']['Page']['media']:
            cache[media['id']] = media
        time.sleep(1.2)
    json.dump(cache, open(cache_file, 'w', encoding='utf-8'), ensure_ascii=False)
    return cache


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('links')
    ap.add_argument('--cache', default='pending-anilist.json')
    ap.add_argument('--sample', type=int, default=15)
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding='utf-8')
    rows = json.load(gzip.open(args.links, 'rt', encoding='utf-8'))['links']
    pending = [r for r in rows if r['estado'] == 'pendiente' and r.get('anilistId')]
    media = fetch(sorted({r['anilistId'] for r in pending}), args.cache)
    applied = collections.defaultdict(list)
    for r in rows:
        if r['estado'] == 'aplicado' and r.get('anilistId'):
            applied[r['anilistId']].append((r['fuente'], r['slug']))
    kinds = collections.Counter()
    samples = collections.defaultdict(list)
    future = collections.Counter()
    future_samples = collections.defaultdict(list)
    for r in pending:
        m = media.get(r['anilistId'])
        if not m:
            kinds['sin datos de AniList'] += 1
            continue
        slug_words = norm(r['slug'].replace('-', ' ')).split()
        want_season, want_base = season_of(slug_words), base_words(slug_words)
        names = [m['title'].get(k) for k in ('romaji', 'english', 'native')] + list(m.get('synonyms') or [])
        best = (0.0, 1)
        for n in names:
            words = norm(n).split()
            s = similarity(want_base, base_words(words))
            if s > best[0]:
                best = (s, season_of(words))
        sim, season = best
        if sim >= 0.8 and season != want_season:
            kind = 'temporada'
        elif sim >= 0.8:
            kind = 'titulo'
        else:
            kind = 'otro'
        if r['anilistId'] in applied:
            kind += ' (el anime ya está enlazado a otras fichas)'
        # Con el criterio propuesto: parecido = el mayor entre las palabras y las letras pegadas; otra temporada no se propone.
        best2 = (0.0, 1)
        for n in names:
            words = norm(n).split()
            d = dice(compact(slug_words), compact(words))
            s2 = max(similarity(want_base, base_words(words)), d if d >= 0.93 else 0.0)
            if s2 > best2[0]:
                best2 = (s2, season_of(words))
        sim2, season2 = best2
        if season2 != want_season:
            new = 'otra temporada: no se propone'
        elif sim2 >= 0.97:
            new = 'seguro'
        elif sim2 >= 0.85:
            new = 'probable (se enlaza)'
        elif sim2 >= 0.1:
            new = 'sigue por revisar'
        else:
            new = 'sin resultado'
        if new == 'sigue por revisar':
            # Otra ficha ya enlazada a ese anime con un slug igual o casi igual (mismas palabras base y temporada): la misma serie en otra fuente.
            for f2, s2slug in applied.get(r['anilistId'], []):
                w2 = norm(s2slug.replace('-', ' ')).split()
                if season_of(w2) == want_season and max(similarity(want_base, base_words(w2)), dice(compact(slug_words), compact(w2)) if dice(compact(slug_words), compact(w2)) >= 0.93 else 0.0) >= 0.85:
                    new = 'hermana: otra fuente con el mismo slug ya enlazada'
                    break
        if new == 'sigue por revisar' and re.search(r'(^|-)(movie|pelicula|ova|oav|ona|special|specials|especial|especiales)(-|$)', r['slug']):
            new = 'sigue por revisar: película, OVA o especial'
        future[new] += 1
        future_samples[new].append((r['fuente'], r['slug'], round(sim2, 2), m['title'].get('romaji')))
        kinds[kind] += 1
        samples[kind].append((r['fuente'], r['slug'], round(r['puntuacion'], 2), m['title'].get('romaji'), season))
    print(f'{len(pending)} fichas por revisar, {len(media)} animes candidatos')
    for kind, n in kinds.most_common():
        print(f'{n:5d}  {kind}')
    print('\nCon el criterio propuesto (letras pegadas; otra temporada no se propone):')
    for kind, n in future.most_common():
        print(f'{n:5d}  {kind}')
    for kind, items in future_samples.items():
        print(f'\n== propuesto: {kind}')
        for item in items[:args.sample]:
            print('  ', *item)
    for kind, items in samples.items():
        print(f'\n== {kind}')
        for item in items[:args.sample]:
            print('  ', *item)


if __name__ == '__main__':
    main()
