"""Prepara el fichero que deshace uniones de series hechas por error, comparando el libro de series de ANTES con el de AHORA (solo lectura).

Uso (desde la raíz del repo):
    python tools/audit/audit.py unmerge --before copia_antes/series-ledger.json.gz --after copia_ahora/series-ledger.json.gz --out deshacer-uniones.json

Por cada serie de «ahora» formada por fichas que en «antes» estaban en series distintas, deja la parte que ya tenía su id (o la mayor) y saca el resto
en grupos: cada grupo de fichas que venían juntas pasa a una serie propia. El fichero resultante, `{"groups": [[["fuente","slug"], …], …]}`, se copia a la
carpeta de copias de la TV y se aplica con la acción ADB `SPLIT` (`am broadcast -a app.kaizen.action.SPLIT -n app.kaizen/.control.AdbControlReceiver
--es file deshacer-uniones.json`). No borra nada: solo reparte fichas en series nuevas. Con --list escribe además un resumen legible.
"""
import argparse
import collections
import gzip
import json
import sys


def load(path):
    led = json.load(gzip.open(path, 'rt', encoding='utf-8'))
    idof = {(m['sourceId'], m['slug']): m['seriesId'] for m in led['map']}
    by = collections.defaultdict(list)
    for ficha, sid in idof.items():
        by[sid].append(ficha)
    return idof, by


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--before', required=True)
    ap.add_argument('--after', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--list', help='escribe también un resumen legible')
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding='utf-8')

    before_id, before_by = load(args.before)
    _, after_by = load(args.after)
    groups, lines, merged = [], [], 0
    for sid, fichas in sorted(after_by.items()):
        parts = collections.defaultdict(list)
        for f in fichas:
            if f in before_id:
                parts[before_id[f]].append(f)
        if len(parts) < 2:
            continue
        merged += 1
        keep = sid if sid in parts else max(parts, key=lambda k: len(parts[k]))
        lines.append(f'serie {sid}: {len(fichas)} fichas venían de {len(parts)} series')
        for part_id, members in sorted(parts.items()):
            action = 'se queda' if part_id == keep else 'pasa a serie propia'
            lines.append(f'  - {action}: ' + ', '.join(f'{a}:{s}' for a, s in sorted(members)))
            if part_id != keep:
                groups.append([[a, s] for a, s in sorted(members)])
    json.dump({'groups': groups}, open(args.out, 'w', encoding='utf-8'), ensure_ascii=False)
    print(f'series formadas por varias de antes: {merged} | grupos que pasan a serie propia: {len(groups)} | escrito {args.out}')
    if args.list:
        open(args.list, 'w', encoding='utf-8').write('\n'.join(lines))
        print(f'resumen en {args.list}')


if __name__ == '__main__':
    main()
