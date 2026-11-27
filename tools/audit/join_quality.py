"""Mide la calidad de las uniones de series con AniList y agrupa los errores por tipo (solo lectura).

Uso:  python tools/audit/audit.py quality CARPETA_DE_COPIAS [--backup kaizen-backup-….json] [--cache anilist-media.json]
                                         [--out informe] [--sample 12] [--no-fetch]

CARPETA_DE_COPIAS es la bajada de `backups/` de la TV (`node tools/tv.js backup --confirm` la deja en backups/tv-<fecha>), con
`series-ledger.json.gz` (ficha -> serie), `anilist-links.json.gz` (ficha -> anime de AniList) y las `kaizen-backup-*.json` (reglas del usuario).
Sin --backup se usa la más reciente.

Tres capas, que se miden por separado:
  1. Enlace      ficha (fuente:slug) -> anime de AniList. Se juzga comparando el slug con los títulos y sinónimos del anime
                 (pide a AniList solo los ids, 50 por petición, y los guarda en --cache; con --no-fetch usa solo la caché).
  2. Serie       las fichas que el libro de series junta en una misma serie. Debería ser un anime de AniList por serie.
  3. Cobertura   fichas que no tienen anime de AniList (sin resultado, por revisar, sin fila).

Tipos de error (cada uno con su porcentaje y su CSV):
  A1 mezcla-distinta   una serie junta fichas de animes de AniList sin relación de título (Shingeki no Kyojin con Shakugan no Shana).
  A2 mezcla-franquicia una serie junta animes de AniList con el mismo título base (temporadas, partes, películas); puede ser decisión del usuario.
  B  serie-partida     un mismo anime de AniList está repartido en varias series (falta unir). Se descartan las que el usuario separó a mano.
  C1 enlace-otra-temporada   el slug se parece al título pero la temporada no coincide.
  C2 nombre-alternativo      el slug no se parece, pero otra ficha del mismo anime sí encaja (idioma o título distinto): riesgo bajo.
  C3 sin-respaldo            el slug no se parece y ninguna ficha del anime encaja: a revisar a ojo, por nivel de enlace (qué matcher falla).
  E  fuentes-en-desacuerdo   el mismo slug (sin -latino/-castellano) está enlazado a animes distintos según la fuente: al menos uno está mal.
  D1 sin-enlace-heredable   ficha sin anime pero con una hermana de su serie que sí lo tiene.
  D2 sin-enlace             ficha sin anime y sin hermana enlazada.

Escribe en --out (por defecto CARPETA/informe-calidad): INFORME.md, INFORME.json y un CSV por tipo. No toca la TV ni la app.
"""
import argparse
import csv
import gzip
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from pending import base_words, compact, dice, fetch, norm, season_of, similarity  # noqa: E402  (se reutiliza el parecido de título del matcher)


def load(folder, backup_name):
    ledger = json.load(gzip.open(folder / 'series-ledger.json.gz', 'rt', encoding='utf8'))
    links = json.load(gzip.open(folder / 'anilist-links.json.gz', 'rt', encoding='utf8'))['links']
    backup_file = Path(backup_name) if backup_name else max(folder.glob('kaizen-backup-*.json'), key=lambda p: p.name)
    if backup_name and (folder / backup_name).exists():
        backup_file = folder / backup_name
    backup = json.loads(backup_file.read_text(encoding='utf8'))
    titles = json.loads(backup['titles']) if backup.get('titles') else {}
    return ledger, links, backup, titles, backup_file


def names_of(media):
    return [media['title'].get(k) for k in ('romaji', 'english', 'native') if media['title'].get(k)] + list(media.get('synonyms') or [])


EXTRA_NOISE = {'movie', 'ova', 'oav', 'ona', 'special', 'specials', 'especial', 'especiales', 'pelicula', 'gekijouban', 'tv', 'latino', 'castellano', 'sub', 'the', 'no', 'wa', 'wo', 'ga'}


def core(words):
    """Palabras que identifican el título: sin temporada, parte, formato ni partículas."""
    return {w for w in base_words(words) if w not in EXTRA_NOISE}


def fit(slug, media):
    """(parecido 0..1, ¿misma temporada?) entre el slug de una ficha y el anime de AniList.

    El parecido es el mayor de: palabras en común (Jaccard), letras pegadas (dice >= 0,93) y contención (las palabras del slug o las del
    título están casi todas en el otro, con al menos dos en común: «movie-3-fukaki-tamashii-no-reimei» frente a «Fukaki Tamashii no Reimei»).
    """
    words = norm(slug.replace('-', ' ')).split()
    want_season, want_base, want_compact, want_core = season_of(words), base_words(words), compact(words), core(words)
    best, season = 0.0, 1
    for name in names_of(media):
        w = norm(name).split()
        d = dice(want_compact, compact(w))
        c = core(w)
        shared = len(want_core & c)
        contain = shared / min(len(want_core), len(c)) if want_core and c else 0.0
        s = max(similarity(want_base, base_words(w)), d if d >= 0.93 else 0.0, 0.9 if contain >= 0.8 and shared >= 2 else 0.0)
        if s > best:
            best, season = s, season_of(w)
    return best, season == want_season


FORMATO = {'ova': 'OVA', 'oav': 'OVA', 'ona': 'ONA', 'special': 'SPECIAL', 'specials': 'SPECIAL', 'especial': 'SPECIAL', 'especiales': 'SPECIAL',
           'movie': 'MOVIE', 'pelicula': 'MOVIE', 'peliculas': 'MOVIE', 'film': 'MOVIE'}


def forma(slug):
    """(temporada, formato) que dice el slug; el mismo criterio que `SeriesResolver.sameShape` (AnilistMatcher.split) de la app."""
    words = norm(slug.replace('-', ' ')).split()
    return season_of(words), next((FORMATO[w] for w in words if w in FORMATO), None)


def related(a, b):
    """¿Dos animes de AniList comparten título base (franquicia)? Mira el mejor par de nombres."""
    best = 0.0
    for x in names_of(a):
        bx = base_words(norm(x).split())
        for y in names_of(b):
            best = max(best, similarity(bx, base_words(norm(y).split())))
    return best >= 0.5


def pct(n, total):
    return f'{100 * n / total:.1f}%' if total else '—'


def write_csv(out, name, rows):
    if not rows:
        return
    with open(out / name, 'w', newline='', encoding='utf8') as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('folder')
    ap.add_argument('--backup')
    ap.add_argument('--cache', default='anilist-media.json')
    ap.add_argument('--out')
    ap.add_argument('--sample', type=int, default=12)
    ap.add_argument('--no-fetch', action='store_true', help='no pide nada a AniList: solo usa la caché')
    ap.add_argument('--split', help='JSON de la acción SPLIT ya preparado (p. ej. split-depurado.json): marca los errores A que ese plan ya separa')
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding='utf-8')
    folder = Path(a.folder)
    ledger, links, backup, titles, backup_file = load(folder, a.backup)
    serie_de = {(m['sourceId'], m['slug']): str(m['seriesId']) for m in ledger['map']}
    fichas_de = defaultdict(list)
    for key, sid in serie_de.items():
        fichas_de[sid].append(key)
    link_de = {(l['fuente'], l['slug']): l for l in links}
    aplicado = {k: l for k, l in link_de.items() if l['estado'] == 'aplicado' and l.get('anilistId')}

    ids = sorted({l['anilistId'] for l in aplicado.values()})
    if a.no_fetch:
        import os
        cache = {int(k): v for k, v in json.load(open(a.cache, encoding='utf-8')).items()} if os.path.exists(a.cache) else {}
    else:
        cache = fetch(ids, a.cache)
    media = {i: cache[i] for i in ids if i in cache}
    sin_datos = [i for i in ids if i not in media]

    total = len(serie_de)
    out = Path(a.out) if a.out else folder / 'informe-calidad'
    out.mkdir(parents=True, exist_ok=True)
    res = {'copia': backup_file.name, 'fichas': total, 'series': len(fichas_de), 'con_enlace_aplicado': len(aplicado),
           'animes_distintos': len(ids), 'animes_sin_datos_de_anilist': len(sin_datos),
           'fichas_sin_fila_de_enlace': sum(1 for k in serie_de if k not in link_de), 'enlaces_sin_ficha_en_el_libro': sum(1 for k in link_de if k not in serie_de)}
    samples = defaultdict(list)

    # ---- Capa 2: series mezcladas (A1/A2) -------------------------------------------------------------------------------------------
    en_plan = set()
    if a.split:
        en_plan = {tuple(f) for g in json.load(open(a.split, encoding='utf8'))['groups'] for f in g}
    user_groups = {frozenset(tuple(f.split(':', 1)) for f in g) for g in backup.get('groups', [])}
    user_ficha = {f for g in user_groups for f in g}
    a_rows, minority_fichas = [], {'A1': 0, 'A2': 0}
    series_a, plan_cubierto = Counter(), Counter()
    for sid, fichas in fichas_de.items():
        parts = defaultdict(list)
        for f in fichas:
            if f in aplicado:
                parts[aplicado[f]['anilistId']].append(f)
        if len(parts) < 2:
            continue
        ordered = sorted(parts.items(), key=lambda kv: (-len(kv[1]), kv[0]))
        mayor = ordered[0][0]
        todos = [i for i, _ in ordered]
        relacionados = all(i in media for i in todos) and all(related(media[mayor], media[i]) for i in todos[1:])
        tipo = 'A2' if relacionados else 'A1'
        decidida = any(f in user_ficha for fs in parts.values() for f in fs)
        minoria = sum(len(fs) for _, fs in ordered[1:])
        planeada = all(tuple(f) in en_plan for _, fs in ordered[1:] for f in fs) if en_plan else False
        if planeada:
            plan_cubierto[tipo] += minoria
        minority_fichas[tipo] += minoria
        series_a[tipo] += 1
        row = {'tipo': tipo, 'serie': sid, 'fichas': len(fichas), 'animes': len(parts), 'fichas_minoritarias': minoria, 'regla_del_usuario': 'sí' if decidida else 'no', 'ya_en_el_plan_split': 'sí' if planeada else 'no',
               'partes': ' || '.join(f'{i} {media[i]["title"].get("romaji") if i in media else "?"} [{media[i].get("format") if i in media else "?"}] ({len(fs)}): '
                                    + ', '.join(sorted({s for _, s in fs})[:3]) for i, fs in ordered)}
        a_rows.append(row)
        samples[tipo].append(row['partes'][:230])
    write_csv(out, 'A-series-mezcladas.csv', sorted(a_rows, key=lambda r: (r['tipo'], -r['fichas_minoritarias'])))

    # ---- Capa 2: animes repartidos en varias series (B) -----------------------------------------------------------------------------
    series_de_anime = defaultdict(set)
    for f, l in aplicado.items():
        series_de_anime[l['anilistId']].add(serie_de[f]) if f in serie_de else None
    splits = [set(map(str, s)) for s in titles.get('splits', [])]
    b_rows = []
    for aid, series in series_de_anime.items():
        if len(series) < 2:
            continue
        separada_por_usuario = any(len(s & series) >= 2 for s in splits)
        fuentes = [sorted({f[0] for f in fichas_de[s] if f in aplicado and aplicado[f]['anilistId'] == aid}) for s in sorted(series)]
        solapan = len(set(sum(fuentes, []))) < len(sum(fuentes, []))  # una misma fuente en dos series: dos fichas de esa web para el anime (OVA, parte, duplicada)
        solo_ese = all({aplicado[x]['anilistId'] for x in fichas_de[s_] if x in aplicado} == {aid} and all(x in aplicado for x in fichas_de[s_]) for s_ in series)
        # La regla de la app (SeriesResolver): dos fichas de una misma fuente en series distintas se unen si dicen la misma temporada y formato.
        por_fuente = defaultdict(list)
        for s_ in series:
            for x in fichas_de[s_]:
                if x in aplicado and aplicado[x]['anilistId'] == aid:
                    por_fuente[x[0]].append((s_, forma(x[1])))
        bloqueada = any(len({sh for _, sh in v}) > 1 for v in por_fuente.values() if len({s_ for s_, _ in v}) > 1)
        row = {'anilistId': aid, 'titulo': media[aid]['title'].get('romaji') if aid in media else '?', 'formato': media[aid].get('format') if aid in media else '?',
               'series': len(series), 'fichas': sum(len([f for f in fichas_de[s] if f in aplicado and aplicado[f]['anilistId'] == aid]) for s in series),
               'separada_por_usuario': 'sí' if separada_por_usuario else 'no', 'misma_fuente_repetida': 'sí' if solapan else 'no', 'fusion_limpia': 'sí' if solo_ese else 'no', 'la_regla_los_une': 'no' if bloqueada else 'sí',
               'detalle': ' | '.join(f'{s}: {", ".join(f"{x[0]}:{x[1]}" for x in fichas_de[s] if x in aplicado and aplicado[x]["anilistId"] == aid)}' for s in sorted(series))}
        b_rows.append(row)
        if not separada_por_usuario:
            samples['B'].append(f'{aid} {row["titulo"]} -> {row["detalle"][:170]}')
    write_csv(out, 'B-animes-partidos.csv', sorted(b_rows, key=lambda r: -r['series']))
    b_real = [r for r in b_rows if r['separada_por_usuario'] == 'no']
    b_series_sobrantes = sum(r['series'] - 1 for r in b_real)
    b_fichas_fuera = sum(r['fichas'] for r in b_real)
    b_cruzadas = [r for r in b_real if r['misma_fuente_repetida'] == 'no']
    b_limpias = [r for r in b_real if r['fusion_limpia'] == 'sí']
    b_regla = [r for r in b_real if r['la_regla_los_une'] == 'sí']

    # ---- Capa 1: enlaces cuyo slug no se parece al anime (C) ------------------------------------------------------------------------
    c_rows, por_nivel = [], defaultdict(Counter)
    ajuste = {f: fit(f[1], media[l['anilistId']]) for f, l in aplicado.items() if l['anilistId'] in media}
    respaldado = {aplicado[f]['anilistId'] for f, (sim, misma) in ajuste.items() if sim >= 0.85 and misma}  # anime con alguna ficha cuyo slug sí encaja
    for f, l in aplicado.items():
        m = media.get(l['anilistId'])
        if not m:
            por_nivel[l['nivel']]['sin datos'] += 1
            continue
        sim, misma = ajuste[f]
        if sim >= 0.85 and misma:
            tipo = 'ok'
        elif sim >= 0.85:
            tipo = 'C1'
        else:
            tipo = 'C2' if l['anilistId'] in respaldado else 'C3'
        por_nivel[l['nivel']][tipo] += 1
        if tipo != 'ok':
            row = {'tipo': tipo, 'fuente': f[0], 'slug': f[1], 'anilistId': l['anilistId'], 'anime': m['title'].get('romaji'), 'formato': m.get('format'),
                   'anio': m.get('seasonYear'), 'nivel': l['nivel'], 'origen': l['origen'], 'parecido': round(sim, 2)}
            c_rows.append(row)
            samples[tipo + '-' + l['nivel']].append(f'{f[0]}:{f[1]}  ->  {l["anilistId"]} {row["anime"]} ({row["parecido"]})')
    write_csv(out, 'C-enlaces-dudosos.csv', sorted(c_rows, key=lambda r: (r['tipo'], r['nivel'], r['parecido'])))

    # ---- Capa 1: la misma ficha en varias fuentes apunta a animes distintos (E) -------------------------------------------------------
    e_grupos = defaultdict(list)
    for f, l in aplicado.items():
        base = '-'.join(w for w in f[1].split('-') if w not in ('latino', 'castellano', 'sub', 'espanol'))
        e_grupos[base].append((f, l['anilistId']))
    e_rows = []
    for base, items in e_grupos.items():
        votos = Counter(i for _, i in items)
        if len(votos) < 2 or len({f[0] for f, _ in items}) < 2:
            continue
        ganador = votos.most_common(1)[0][0]
        for f, i in items:
            if i != ganador:
                e_rows.append({'slug_base': base, 'fuente': f[0], 'slug': f[1], 'anilistId': i, 'anime': media[i]['title'].get('romaji') if i in media else '?',
                               'mayoria': ganador, 'anime_mayoria': media[ganador]['title'].get('romaji') if ganador in media else '?',
                               'votos': ' '.join(f'{k}x{v}' for k, v in votos.most_common()), 'nivel': aplicado[f]['nivel'], 'origen': aplicado[f]['origen']})
                samples['E-' + aplicado[f]['nivel']].append(f'{f[0]}:{f[1]}  ->  {i} {e_rows[-1]["anime"]}   (mayoría {ganador} {e_rows[-1]["anime_mayoria"]}; {e_rows[-1]["votos"]})')
    write_csv(out, 'E-fuentes-en-desacuerdo.csv', e_rows)

    # ---- Capa 3: fichas sin anime de AniList (D) ------------------------------------------------------------------------------------
    d_rows = []
    for f, sid in serie_de.items():
        if f in aplicado:
            continue
        l = link_de.get(f)
        hermanas = {aplicado[g]['anilistId'] for g in fichas_de[sid] if g in aplicado}
        tipo = 'D1' if hermanas else 'D2'
        cand = (l or {}).get('anilistId')
        # La regla de la app (AnilistReview.completions): ficha por revisar con candidato = anime de una hermana y parecido >= 0,85, sin choque de formato.
        m = media.get(cand) if cand else None
        choque = bool(m) and ((forma(f[1])[1] in ('MOVIE', 'OVA', 'SPECIAL') and m.get('format') in ('TV', 'TV_SHORT')))
        adoptable = bool(cand and cand in hermanas and l and l['estado'] == 'pendiente' and l['origen'] != 'usuario' and l['puntuacion'] >= 0.85 and not choque)
        row = {'tipo': tipo, 'fuente': f[0], 'slug': f[1], 'serie': sid, 'la_app_la_completa': 'sí' if adoptable else 'no', 'estado': l['estado'] if l else 'sin fila', 'nivel': l['nivel'] if l else '',
               'candidato': cand or '', 'candidato_ya_en_la_serie': 'sí' if cand and cand in hermanas else 'no', 'hermanas_con_anime': ' '.join(map(str, sorted(hermanas)))}
        d_rows.append(row)
        samples[tipo + '-' + row['estado']].append(f'{f[0]}:{f[1]} (serie {sid}; hermanas {row["hermanas_con_anime"] or "—"}; candidato {row["candidato"] or "—"})')
    write_csv(out, 'D-fichas-sin-enlace.csv', sorted(d_rows, key=lambda r: (r['tipo'], r['estado'], r['slug'])))

    # ---- Resumen --------------------------------------------------------------------------------------------------------------------
    c1 = [r for r in c_rows if r['tipo'] == 'C1']
    c2 = [r for r in c_rows if r['tipo'] == 'C2']
    c3 = [r for r in c_rows if r['tipo'] == 'C3']
    d1 = [r for r in d_rows if r['tipo'] == 'D1']
    d2 = [r for r in d_rows if r['tipo'] == 'D2']
    d1_resolubles = [r for r in d1 if r['candidato_ya_en_la_serie'] == 'sí']
    d1_app = [r for r in d1 if r['la_app_la_completa'] == 'sí']
    n_ap = len(aplicado)
    res.update({
        'A1_series': series_a['A1'], 'A1_fichas_minoritarias': minority_fichas['A1'], 'A2_series': series_a['A2'], 'A2_fichas_minoritarias': minority_fichas['A2'],
        'B_animes_partidos': len(b_real), 'B_series_sobrantes': b_series_sobrantes, 'B_fichas_afectadas': b_fichas_fuera, 'B_animes_partidos_entre_fuentes_distintas': len(b_cruzadas),
        'C1_enlaces': len(c1), 'C2_enlaces': len(c2), 'C3_enlaces': len(c3), 'B_fusion_limpia': len(b_limpias), 'B_que_une_la_regla_de_la_app': len(b_regla),
        'A1_cubierto_por_el_plan_split': plan_cubierto['A1'], 'A2_cubierto_por_el_plan_split': plan_cubierto['A2'], 'E_enlaces_en_desacuerdo': len(e_rows), 'E_slugs_base_en_desacuerdo': len({r['slug_base'] for r in e_rows}), 'D1_fichas': len(d1), 'D1_candidato_ya_en_la_serie': len(d1_resolubles), 'D1_que_completa_la_app': len(d1_app), 'D2_fichas': len(d2),
        'C_por_nivel': {k: dict(v) for k, v in por_nivel.items()}, 'D_por_estado': dict(Counter(r['estado'] for r in d_rows))})
    (out / 'INFORME.json').write_text(json.dumps(res, indent=2, ensure_ascii=False), encoding='utf8')

    L = [f'# Calidad de las uniones con AniList', '', f'Copia: `{backup_file.name}` · fichas {total} · series {len(fichas_de)} · enlaces aplicados {n_ap} ({len(ids)} animes; {len(sin_datos)} sin datos de AniList)', '',
         '| Tipo | Qué es | Unidad | Casos | % | Sobre |', '|---|---|---|---:|---:|---|']
    L += [f'| A1 | serie con animes sin relación | fichas minoritarias | {minority_fichas["A1"]} | {pct(minority_fichas["A1"], n_ap)} | fichas enlazadas |',
          f'| A2 | serie con animes de la misma franquicia | fichas minoritarias | {minority_fichas["A2"]} | {pct(minority_fichas["A2"], n_ap)} | fichas enlazadas |',
          f'| B | anime repartido en varias series | series sobrantes | {b_series_sobrantes} | {pct(b_series_sobrantes, len(fichas_de))} | series |',
          f'| C1 | enlace a otra temporada del mismo título | enlaces | {len(c1)} | {pct(len(c1), n_ap)} | enlaces aplicados |',
          f'| C2 | slug distinto, pero otra ficha del anime sí encaja (nombre alternativo) | enlaces | {len(c2)} | {pct(len(c2), n_ap)} | enlaces aplicados |',
          f'| C3 | slug distinto y ninguna ficha del anime encaja (sin respaldo) | enlaces | {len(c3)} | {pct(len(c3), n_ap)} | enlaces aplicados |',
          f'| E | mismo slug en varias fuentes, anime distinto (el de la minoría) | enlaces | {len(e_rows)} | {pct(len(e_rows), n_ap)} | enlaces aplicados |',
          f'| D1 | sin anime, hermana con anime (candidato ya en la serie: {len(d1_resolubles)}; los completa la app: {len(d1_app)}) | fichas | {len(d1)} | {pct(len(d1), total)} | fichas |',
          f'| D2 | sin anime, sin hermana | fichas | {len(d2)} | {pct(len(d2), total)} | fichas |', '']
    L += [f'Cubierto por el plan SPLIT indicado: A1 {plan_cubierto["A1"]} fichas, A2 {plan_cubierto["A2"]} fichas. B con fusión limpia (todas las series implicadas solo tienen fichas de ese anime): {len(b_limpias)} de {len(b_real)} animes; la regla actual de la app (misma fuente con la misma temporada y formato) los une en {len(b_regla)}.', '']
    L += ['## Enlaces (C) por nivel del enlace', '', '| nivel | ok | C1 | C2 | C3 | sin datos |', '|---|---:|---:|---:|---:|---:|']
    for nivel, c in sorted(por_nivel.items(), key=lambda kv: -sum(kv[1].values())):
        L.append(f'| {nivel} | {c["ok"]} | {c["C1"]} | {c["C2"]} | {c["C3"]} | {c["sin datos"]} |')
    L += ['', '## Sin enlace (D) por estado', ''] + [f'- {k}: {v}' for k, v in Counter(r['estado'] for r in d_rows).most_common()]
    L += ['', f'## Muestras (hasta {a.sample} por tipo)']
    for k in sorted(samples):
        L += ['', f'### {k} ({len(samples[k])})'] + [f'- {s}' for s in samples[k][:a.sample]]
    (out / 'INFORME.md').write_text('\n'.join(L), encoding='utf8')
    print('\n'.join(L[:40]))
    print('Escrito en', out)


if __name__ == '__main__':
    main()
