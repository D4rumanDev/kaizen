"""Mide cuántas series del libro de series de Kaizen se pueden emparejar con un id de AniList (solo lectura).

Uso:  python tools/audit/audit.py match LIBRO.json.gz [--sample 300] [--seed 1] [--out resultado.csv]

- LIBRO.json.gz es `series-ledger.json.gz` de una copia de seguridad (fichas, ids y uniones).
- Por cada serie de la muestra se toma el título de sus fichas (a partir del slug) y se busca en la API pública de AniList
  (https://graphql.anilist.co, sin cuenta; límite 90 peticiones/min: el script espera entre peticiones y respeta Retry-After).
- Solo se envían títulos sacados de slugs de anime. No toca la app, el tvbox ni ninguna base de datos.

Clasificación del resultado (de más a menos fiable):
  seguro    el título de una ficha coincide exacto (normalizado) con un título o sinónimo del primer resultado, y si la serie tiene
            otra ficha con otro slug, esa también apunta al mismo resultado
  probable  coincide exacto pero otra ficha de la serie apunta a otro resultado, o el parecido de palabras es >= 0,85
  dudoso    hay resultado pero el parecido es menor
  ninguno   AniList no devolvió nada
"""
import argparse
import csv
import gzip
import json
import random
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.request
from collections import Counter, defaultdict

API = "https://graphql.anilist.co"
QUERY = """
query ($search: String) {
  Page(perPage: 8) {
    media(search: $search, type: ANIME, sort: SEARCH_MATCH) {
      id format seasonYear title { romaji english native } synonyms
    }
  }
}"""


def normalize(text):
    text = unicodedata.normalize("NFKD", text or "").encode("ascii", "ignore").decode().lower()
    return " ".join(re.sub(r"[^a-z0-9]+", " ", text).split())


ORDINALS = {"segunda": 2, "tercera": 3, "cuarta": 4, "quinta": 5, "second": 2, "third": 3, "fourth": 4, "fifth": 5, "ii": 2, "iii": 3, "iv": 4}
FORMAT_WORDS = {"ova": "OVA", "oav": "OVA", "ona": "ONA", "special": "SPECIAL", "specials": "SPECIAL", "especial": "SPECIAL", "especiales": "SPECIAL",
                "movie": "MOVIE", "pelicula": "MOVIE", "peliculas": "MOVIE", "film": "MOVIE"}
NOISE = {"tv", "reproduction", "temporada", "season", "part", "parte", "cour", "the", "no", "wa", "ga", "to", "wo", "ni", "de", "ka", "mo"}


def fold(token):
    """Pliega variantes de romanización (ou/oo -> o, uu -> u) para que «mahou shoujo» y «maho shojo» coincidan."""
    token = re.sub(r"ou|oo", "o", token)
    return re.sub(r"uu", "u", token)


def split_title(text):
    """Separa un título normalizado en (palabras base plegadas, número de temporada o 1, formato o None)."""
    words = text.split()
    season, fmt, base = 1, None, []
    i = 0
    while i < len(words):
        w = words[i]
        m = re.fullmatch(r"(\d+)(st|nd|rd|th)", w) or re.fullmatch(r"s(\d)", w)
        if w in ("season", "temporada") and i + 1 < len(words) and words[i + 1].isdigit():
            season = int(words[i + 1]); i += 2; continue
        if m:
            season = int(m.group(1)); i += 1; continue
        if w in ORDINALS and (i + 1 < len(words) and words[i + 1] in ("temporada", "season") or w in ("ii", "iii", "iv")):
            season = ORDINALS[w]; i += 1; continue
        if w in FORMAT_WORDS:
            fmt = FORMAT_WORDS[w]; i += 1; continue
        if w not in NOISE:
            base.append(fold(w))
        i += 1
    return base, season, fmt


def resolve(redirects, ident):
    seen = set()
    while ident in redirects and ident not in seen:
        seen.add(ident)
        ident = redirects[ident]
    return ident


def load_series(path):
    data = json.load(gzip.open(path, "rt", encoding="utf-8"))
    redirects = {r["fromId"]: r["toId"] for r in data["redirects"]}
    series = defaultdict(list)
    for row in data["map"]:
        series[resolve(redirects, row["seriesId"])].append((row["sourceId"], row["slug"]))
    return series


def search(title):
    body = json.dumps({"query": QUERY, "variables": {"search": title}}).encode()
    request = urllib.request.Request(API, body, {"Content-Type": "application/json", "Accept": "application/json", "User-Agent": "kaizen-match/1.0"})
    for _ in range(4):
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                return json.load(response)["data"]["Page"]["media"]
        except urllib.error.HTTPError as error:
            if error.code == 429:
                time.sleep(int(error.headers.get("Retry-After", "30")) + 1)
                continue
            if error.code >= 500:  # AniList falla con ciertas búsquedas: se cuenta como sin resultado
                return []
            raise
        except (urllib.error.URLError, TimeoutError):
            time.sleep(5)
    return []


def variants(media):
    t = media["title"]
    return {normalize(v) for v in [t.get("romaji"), t.get("english"), t.get("native")] + list(media.get("synonyms") or []) if v}


def similarity(a, b):
    wa, wb = set(a.split()), set(b.split())
    return len(wa & wb) / len(wa | wb) if wa and wb else 0.0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("ledger")
    parser.add_argument("--sample", type=int, default=300)
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--out", default="anilist_match.csv")
    args = parser.parse_args()

    series = load_series(args.ledger)
    ids = sorted(series)
    random.Random(args.seed).shuffle(ids)
    sample = ids[: args.sample]
    print(f"{len(series)} series en el libro; muestra de {len(sample)}", file=sys.stderr)

    results = []
    for n, ident in enumerate(sample, 1):
        fichas = series[ident]
        slugs = sorted({slug for _, slug in fichas}, key=len)  # el más corto suele ser el título sin sufijos
        titles = [normalize(slug.replace("-", " ")) for slug in slugs[:2]]
        hits = []
        for title in titles:
            time.sleep(0.8)
            hits.append(search(title))
        if not hits[0]:  # sin resultado: se reintenta sin ruido, con las primeras palabras y con las últimas (los slugs arrastran sufijos y subtítulos)
            words = titles[0].split()
            clean = [w for w in words if w not in NOISE and w not in FORMAT_WORDS and w not in ORDINALS]
            for attempt in (clean, words[:3], words[:2], words[-3:], clean[:2]):
                if attempt and " ".join(attempt) != titles[0]:
                    time.sleep(0.8)
                    hits[0] = search(" ".join(attempt))
                    if hits[0]:
                        break
        want_words, want_season, want_fmt = split_title(titles[0])
        top, score, kind = None, 0.0, ""
        for cand in hits[0]:
            best = 0.0
            for name in variants(cand):
                words, season, fmt = split_title(name)
                sim = len(set(words) & set(want_words)) / len(set(words) | set(want_words)) if words and want_words else 0.0
                # temporada y formato coherentes suman; incoherentes restan
                sim += 0.03 if season == want_season else -0.25
                if want_fmt:
                    sim += 0.03 if (cand.get("format") or "").upper().startswith(want_fmt[:3]) else -0.15
                best = max(best, sim)
            if best > score:
                top, score = cand, best
        others_agree = True
        for extra in hits[1:]:
            ew, es, ef = split_title(titles[1]) if len(titles) > 1 else (want_words, want_season, want_fmt)
            others_agree = others_agree and bool(extra) and any(
                c["id"] == (top or {}).get("id") for c in extra[:3])
        verdict = "ninguno" if top is None else "seguro" if score >= 1.0 and others_agree else "probable" if score >= 0.85 else "dudoso"
        results.append((ident, len(fichas), titles[0], top["id"] if top else "", (top or {}).get("title", {}).get("romaji", ""),
                        (top or {}).get("seasonYear") or "", (top or {}).get("format") or "", round(score, 2), verdict))
        if n % 25 == 0:
            print(f"  {n}/{len(sample)}", file=sys.stderr)

    with open(args.out, "w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["serie", "fichas", "titulo_slug", "anilist_id", "anilist_romaji", "año", "formato", "parecido", "veredicto"])
        writer.writerows(results)
    counts = Counter(r[-1] for r in results)
    ids_seen = Counter(r[3] for r in results if r[3])
    print(f"\nResultado sobre {len(results)} series:")
    for verdict in ("seguro", "probable", "dudoso", "ninguno"):
        print(f"  {verdict:9s} {counts[verdict]:4d}  {100 * counts[verdict] / len(results):5.1f} %")
    repeated = sum(1 for count in ids_seen.values() if count > 1)
    print(f"  ids de AniList repetidos entre series distintas de la muestra: {repeated}")
    print(f"Detalle en {args.out}")


if __name__ == "__main__":
    main()
