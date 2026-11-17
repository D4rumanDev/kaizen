"""Revisa la nomenclatura de los slugs de las fuentes de Kaizen (solo lectura).

Uso:  python tools/audit/audit.py slugs CARPETA_DE_COPIAS [--top 40]

Lee `series-ledger.json.gz` (copia de la TV: ver tools/audit) y cuenta, por fuente, qué palabras usan los slugs para decir
temporada, parte, versión o formato. Replica a mano las reglas de `AnilistMatcher.split` (Kotlin) para señalar los marcadores que
el analizador NO reconoce: sirven para ampliar sus reglas. Si cambias las reglas en Kotlin, actualiza también las de aquí.
"""
import argparse
import gzip
import json
import re
from collections import Counter, defaultdict
from pathlib import Path

ORD_SUFFIX = re.compile(r'(\d+)(st|nd|rd|th|ra|da|nda|era|er|ta|to|vo|mo|ma|va|na)')
SEASON_SHORT = re.compile(r's(\d)')
ORDINALS = {'segunda', 'tercera', 'cuarta', 'quinta', 'sexta', 'septima', 'octava', 'novena', 'decima', 'second', 'third', 'fourth', 'fifth',
            'sixth', 'seventh', 'eighth', 'ninth', 'tenth', 'ii', 'iii', 'iv', 'vi', 'vii', 'viii', 'ix'}
FORMAT = {'ova', 'oav', 'ona', 'special', 'specials', 'especial', 'especiales', 'movie', 'pelicula', 'peliculas', 'film'}
NOISE = {'tv', 'reproduction', 'temporada', 'season', 'part', 'parte', 'cour', 'the', 'no', 'wa', 'ga', 'to', 'wo', 'ni', 'de', 'ka', 'mo'}
VERSION = {'latino', 'castellano', 'sub', 'espanol', 'online', 'hd'}
# Marcadores que NO entiende el analizador pero que parecen de temporada/parte/versión.
CANDIDATES = re.compile(
    r'^(?:final|ultimate|zero|kai|shippuden|remake|reboot|specials?|recap|extra|uncut|uncensored|director|cut|complete|completa|'
    r'primera|primer|segundo|tercero|ultima|ultimo|nueva|new|neo|alter|next|gaiden|side|story|arc|saga|movie|pelicula|oad|oav|ova|ona|'
    r'\d+(?:st|nd|rd|th|ra|da|er|ta|to|vo|mo)?|[ivx]+|part|parte|cour|season|temporada|s\d+|2nda|dub|dubbed|latino|castellano)$')


def tokens(slug):
    return [t for t in slug.split('-') if t]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('folder')
    ap.add_argument('--top', type=int, default=40)
    a = ap.parse_args()
    ledger = json.load(gzip.open(Path(a.folder) / 'series-ledger.json.gz'))
    slugs = [(m['sourceId'], m['slug']) for m in ledger['map']]
    per_source = Counter(s for s, _ in slugs)
    print('Fichas por fuente:', dict(per_source))

    last = Counter()
    unknown = Counter()
    examples = defaultdict(list)
    handled = Counter()
    for src, slug in slugs:
        t = tokens(slug)
        for i, w in enumerate(t):
            nxt = t[i + 1] if i + 1 < len(t) else None
            known = (w in ('season', 'temporada') and nxt and nxt.isdigit()) or ORD_SUFFIX.fullmatch(w) or SEASON_SHORT.fullmatch(w) \
                or w in ORDINALS or w in FORMAT or w in NOISE or w in VERSION or (w.isdigit() and nxt in ('temporada', 'season'))
            if known:
                handled[w] += 1
            elif CANDIDATES.match(w) or (w.isdigit() and i == len(t) - 1):
                unknown[w] += 1
                if len(examples[w]) < 3:
                    examples[w].append(f'{src}:{slug}')
        if len(t) > 1:
            last[t[-1]] += 1
    print(f'\nÚltima palabra más repetida (top {a.top}):')
    for w, n in last.most_common(a.top):
        print(f'  {n:5d}  {w}')
    print('\nMarcadores que el analizador NO entiende (con ejemplos):')
    for w, n in unknown.most_common(a.top):
        print(f'  {n:5d}  {w:14s} {examples[w]}')
    print('\nMarcadores que SÍ entiende (recuento):')
    for w, n in handled.most_common(25):
        print(f'  {n:5d}  {w}')


if __name__ == '__main__':
    main()
