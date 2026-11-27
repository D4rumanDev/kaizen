#!/usr/bin/env python3
"""Cobertura de un «Mejores» hecho desde AniList (solo lectura).

Pide a AniList (API pública, sin datos del usuario) los N animes mejor puntuados y los cruza con una copia de la TV
(`series-ledger.json.gz` y `anilist-links.json.gz` de una carpeta `backups/tv-<fecha>`):
  - sin ficha: ningún enlace aplicado a ese anime, no habría tarjeta;
  - una serie: se resuelve a una sola serie del libro, caso limpio;
  - varias series: el mismo anime está enlazado desde fichas de series distintas, caso que hoy da tarjetas repetidas;
  - solo dudosos: ya no ocurre (solo entran enlaces aplicados; «mal» = id de MyAnimeList, fiable).
Además lista los animes distintos de AniList cuyas fichas están en una sola serie (una serie junta varios: unión sospechosa).

Uso: python -I tools/audit/best_coverage.py <carpeta de la copia> [--top 300] [--popularidad 20000] [--sin-red <fichero.json>]
  --sin-red usa un volcado guardado antes (se escribe junto a la copia como top-anilist.json al pedirlo por red).
No escribe en la TV ni en la copia: solo lee, y guarda el top pedido junto al script de salida si se le indica --guardar.
"""
import argparse
import collections
import gzip
import json
import sys
import time
import urllib.error
import urllib.request

API = "https://graphql.anilist.co"
UA = "kaizen-tools/1.0"  # genérico: nada del usuario en la cabecera
QUERY = """query($page:Int,$pop:Int){Page(page:$page,perPage:50){media(type:ANIME,isAdult:false,sort:SCORE_DESC,popularity_greater:$pop,format_in:[TV,MOVIE,OVA,ONA]){id title{romaji} averageScore format seasonYear}}}"""
# Solo cuentan los enlaces aplicados. El nivel «mal» es el enlace por id de MyAnimeList (fiable), no «malo»; los dudosos son «pendiente» y ya no entran.
DUDOSOS = set()


def pedir_top(n, popularidad):
    out = []
    page = 1
    while len(out) < n:
        body = json.dumps({"query": QUERY, "variables": {"page": page, "pop": popularidad}}).encode()
        req = urllib.request.Request(API, data=body, headers={"Content-Type": "application/json", "Accept": "application/json", "User-Agent": UA})
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                media = json.load(r)["data"]["Page"]["media"]
        except urllib.error.HTTPError as e:
            cab = {k: v for k, v in e.headers.items() if k.lower().startswith(("retry-after", "x-ratelimit"))}
            sys.exit(f"AniList respondió {e.code} en la página {page} (llevaba {len(out)} animes). Cabeceras: {cab}. No se reintenta: espera y vuelve a lanzar.")
        if not media:
            break
        out.extend(media)
        page += 1
        time.sleep(1.2)  # el límite público es de 90 peticiones por minuto
    return out[:n]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("copia")
    ap.add_argument("--top", type=int, default=300)
    ap.add_argument("--popularidad", type=int, default=20000)
    ap.add_argument("--sin-red")
    ap.add_argument("--guardar")
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")  # los títulos traen símbolos que la consola de Windows no codifica

    top = json.load(open(a.sin_red, encoding="utf-8")) if a.sin_red else pedir_top(a.top, a.popularidad)
    if a.guardar:
        json.dump(top, open(a.guardar, "w", encoding="utf-8"), ensure_ascii=False)

    ledger = json.load(gzip.open(f"{a.copia}/series-ledger.json.gz"))["map"]
    serie_de = {(r["sourceId"], r["slug"]): r["seriesId"] for r in ledger}
    links = json.load(gzip.open(f"{a.copia}/anilist-links.json.gz"))["links"]

    por_anime = collections.defaultdict(list)  # anilistId -> [(fuente, slug, nivel)]
    for l in links:
        if l.get("estado") == "aplicado" and l.get("anilistId"):
            por_anime[l["anilistId"]].append((l["fuente"], l["slug"], l.get("nivel", "")))

    sin, una, varias, dudosos = [], [], [], []
    for m in top:
        fichas = por_anime.get(m["id"], [])
        if not fichas:
            sin.append(m)
            continue
        buenas = [f for f in fichas if f[2] not in DUDOSOS]
        if not buenas:
            dudosos.append(m)
            continue
        series = {serie_de.get((s, sl)) for s, sl, _ in buenas} - {None}
        (una if len(series) <= 1 else varias).append((m, sorted(series)))

    n = len(top)
    print(f"Top {n} de AniList (popularidad > {a.popularidad}). Copia: {a.copia}")
    print(f"  una serie (limpio):                    {len(una):4d}  {100 * len(una) / n:5.1f} %")
    print(f"  varias series (hoy repetirían):        {len(varias):4d}  {100 * len(varias) / n:5.1f} %")
    print(f"  solo enlaces dudosos:                  {len(dudosos):4d}  {100 * len(dudosos) / n:5.1f} %")
    print(f"  sin ficha enlazada (sin tarjeta):      {len(sin):4d}  {100 * len(sin) / n:5.1f} %")
    for titulo, lista in (("VARIAS SERIES", [(m, s) for m, s in varias]),):
        print(f"\n{titulo}")
        for m, s in lista:
            print(f"  AniList {m['id']:>6} {m['title']['romaji'][:46]:46} series {s}")
    print("\nSIN FICHA ENLAZADA (primeras 25)")
    for m in sin[:25]:
        print(f"  AniList {m['id']:>6} {m['title']['romaji'][:60]} ({m.get('seasonYear')})")
    print("\nSOLO DUDOSOS (primeras 15)")
    for m in dudosos[:15]:
        print(f"  AniList {m['id']:>6} {m['title']['romaji'][:60]}")

    # Una serie del libro que reúne fichas de varios animes de AniList (aplicados y seguros): unión sospechosa.
    animes_de_serie = collections.defaultdict(set)
    for aid, fichas in por_anime.items():
        for s, sl, nivel in fichas:
            if nivel not in DUDOSOS and (s, sl) in serie_de:
                animes_de_serie[serie_de[(s, sl)]].add(aid)
    top_ids = {m["id"] for m in top}
    mezcla = {sid: ids for sid, ids in animes_de_serie.items() if len(ids) > 1 and ids & top_ids}
    print(f"\nSeries del libro con fichas de varios animes de AniList y alguno del top: {len(mezcla)}")


if __name__ == "__main__":
    sys.exit(main())
