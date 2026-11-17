"""Muestrea fichas reales de una web con datos en JavaScript (format jsData, p. ej. animeav1) y resume los valores que usa cada campo.

Uso (desde la raiz del repo; solo lectura; hace N peticiones espaciadas, no es un recorrido):
    python tools/audit/ficha_sample.py BASE N URL_LISTADO [URL_LISTADO ...]
Ejemplo (animeav1):
    python tools/audit/ficha_sample.py https://animeav1.com 14 https://animeav1.com/catalogo?page=1 https://animeav1.com/catalogo?page=40

Lee las series de los listados (objetos con `categoryId` y `slug`), reparte la muestra entre ellos, baja cada ficha (`BASE/media/SLUG`)
con 1,5 s entre peticiones y cuenta, por campo, los valores distintos (estado, categoria, claves de `aka`...) y cuantas fichas lo traen
vacio. Sirve para decidir el mapeo de una web a la estructura de AniList con evidencia (que codigo es «en emision», que formatos hay...).
"""
import re
import sys
import time
from collections import Counter

from ficha_keys import fields, load, media_of

PAUSE_S = 1.5
SIMPLE = ("status", "categoryId", "startDate", "endDate", "runtime", "episodesCount", "mature", "waitDays", "featured", "poster", "backdrop", "trailer", "malId", "seasons")


def pick(items, count):
    """`count` elementos repartidos por toda la lista."""
    if len(items) <= count:
        return list(items)
    step = len(items) / count
    return [items[int(i * step)] for i in range(count)]


def date_shape(value):
    return "null" if value == "null" else re.sub(r"\d", "9", value.strip('"'))


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if len(sys.argv) < 4:
        print(__doc__)
        return 1
    base, count, listings = sys.argv[1].rstrip("/"), int(sys.argv[2]), sys.argv[3:]
    slugs, category_of = [], {}
    for url in listings:
        found = re.findall(r'categoryId:(\d+),slug:"([a-z0-9-]+)"', load(url))
        print(f"{url}: {len(found)} series")
        for category, slug in found:
            if slug not in category_of:
                category_of[slug] = category
                slugs.append(slug)
        time.sleep(PAUSE_S)
    # Al menos dos series de cada categoria (si no, una muestra al azar puede no ver nunca una pelicula o un OVA) y el resto repartido.
    chosen = []
    for category in sorted(set(category_of.values())):
        chosen += [slug for slug in slugs if category_of[slug] == category][:2]
    sample = chosen + [slug for slug in pick(slugs, max(0, count - len(chosen))) if slug not in chosen]
    seen = {name: Counter() for name in SIMPLE}
    akas, categories, extras = Counter(), Counter(), Counter()
    ok = 0
    for slug in sample:
        try:
            media = media_of(load(f"{base}/media/{slug}"))
        except Exception as error:
            print(f"  {slug}: ERROR {error}")
            media = None
        time.sleep(PAUSE_S)
        if not media:
            continue
        ok += 1
        data = dict(fields(media))
        print(f"  {slug[:34]:34} cat={category_of.get(slug)} status={data.get('status')} start={data.get('startDate')} end={data.get('endDate')} "
              f"eps={data.get('episodesCount')} cat={re.sub(r'id:[0-9]+,', '', data.get('category', ''))[:48]}")
        for name in SIMPLE:
            value = data.get(name, "<ausente>")
            seen[name][date_shape(value) if name in ("startDate", "endDate") else value] += 1
        akas.update(re.findall(r'"([a-z]{2}-[a-z0-9]+)":', data.get("aka", "")))
        categories[re.sub(r"id:\d+,", "", data.get("category", "<ausente>"))] += 1
        extras["con aka"] += 1 if data.get("aka", "null") != "null" else 0
        extras["genres vacios"] += 1 if data.get("genres", "[]") in ("[]", "null") else 0
        extras["relations con datos"] += 1 if data.get("relations", "[]") not in ("[]", "null") else 0
        extras["titulo no vacio"] += 1 if data.get("title", '""') not in ('""', "null") else 0
    print(f"\nFichas leidas: {ok} de {len(sample)}")
    for name in SIMPLE:
        shown = ", ".join(f"{v}×{n}" for v, n in seen[name].most_common(8))
        print(f"{name:14} {shown[:230]}")
    print(f"{'aka (claves)':14} {dict(akas)}")
    print(f"{'category':14} {dict(categories)}")
    print(f"{'otros':14} {dict(extras)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
