"""Separa las uniones de series de Kaizen en manuales y automáticas (solo lectura).

Uso:  python tools/audit/audit.py unions CARPETA_DE_COPIAS [--backup kaizen-backup-AAAAMMDD-HHMMSS.json] [--out informe]

CARPETA_DE_COPIAS es la de `backups/` de la app en la TV (adb pull /sdcard/Android/data/app.kaizen/files/backups/.), con
`series-ledger.json.gz` y las `kaizen-backup-*.json`. Sin --backup se usa la más reciente.

Qué es cada cosa (ver SeriesIds.kt y UserTitles.kt):
  - redirects del libro: «el id A ya no es una serie, es B». Cada componente conexo = una serie unida (con sus fichas).
  - titles.merges: grupos de ids (casi siempre pares) que el usuario unió a mano; groups: grupos de fichas (fuente:slug) que el usuario unió a mano.
  - titles.splits: pares de ids que el usuario separó a mano.
Una unión es MANUAL si el usuario unió dos de sus ids (merges) o dos de sus fichas (groups); si no, es AUTOMÁTICA.

Escribe `INFORME.json` (resumen) y `uniones.csv` (una fila por serie unida) en --out (por defecto, la carpeta de copias/informe-uniones).
No toca la TV ni la app.
"""
import argparse
import csv
import gzip
import json
from collections import defaultdict
from pathlib import Path


class UF:
    def __init__(self):
        self.p = {}

    def find(self, x):
        self.p.setdefault(x, x)
        while self.p[x] != x:
            self.p[x] = self.p[self.p[x]]
            x = self.p[x]
        return x

    def union(self, a, b):
        self.p[self.find(a)] = self.find(b)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('folder')
    ap.add_argument('--backup')
    ap.add_argument('--out')
    a = ap.parse_args()
    folder = Path(a.folder)
    ledger = json.load(gzip.open(folder / 'series-ledger.json.gz'))
    backup_file = Path(a.backup) if a.backup else max(folder.glob('kaizen-backup-*.json'), key=lambda p: p.name)
    backup = json.loads(backup_file.read_text(encoding='utf8'))
    titles = json.loads(backup['titles']) if backup.get('titles') else {}
    merges = [tuple(map(str, m)) for m in titles.get('merges', [])]
    splits = [tuple(map(str, s)) for s in titles.get('splits', [])]
    groups = backup.get('groups', [])

    ficha_id = {(m['sourceId'], m['slug']): str(m['seriesId']) for m in ledger['map']}
    by_id = defaultdict(list)
    for (src, slug), sid in ficha_id.items():
        by_id[sid].append(f'{src}:{slug}')

    uf = UF()
    for r in ledger['redirects']:
        uf.union(str(r['fromId']), str(r['toId']))
    members = defaultdict(set)
    for sid in list(by_id) + [str(r['fromId']) for r in ledger['redirects']] + [str(r['toId']) for r in ledger['redirects']]:
        members[uf.find(sid)].add(sid)
    unidas = {root: ids for root, ids in members.items() if len(ids) > 1}

    manual_pairs = defaultdict(int)
    for m in merges:  # cada merge es un grupo de ids (casi siempre un par)
        roots = defaultdict(int)
        for i in m:
            roots[uf.find(i)] += 1
        for root, n in roots.items():
            if n > 1:
                manual_pairs[root] += 1
    manual_groups = defaultdict(int)
    for g in groups:
        ids = {uf.find(ficha_id[tuple(f.split(':', 1))]) for f in g if tuple(f.split(':', 1)) in ficha_id}
        if len(ids) == 1 and len(g) > 1:
            manual_groups[next(iter(ids))] += 1

    rows = []
    for root, ids in unidas.items():
        fichas = sorted(f for i in ids for f in by_id.get(i, []))
        origen = 'manual' if manual_pairs.get(root) or manual_groups.get(root) else 'auto'
        rows.append({'serie': root, 'origen': origen, 'ids': ' '.join(sorted(ids, key=lambda s: int(s))), 'fichas': len(fichas),
                     'merges_manuales': manual_pairs.get(root, 0), 'grupos_manuales': manual_groups.get(root, 0), 'lista': ' | '.join(fichas)})
    split_vs_union = [s for s in splits if len({uf.find(i) for i in s}) < len(s)]
    merges_huerfanos = [m for m in merges if len({uf.find(i) for i in m}) == len(m)]
    resumen = {
        'copia': backup_file.name, 'libro_fichas': len(ledger['map']), 'redirecciones': len(ledger['redirects']),
        'series_unidas': len(rows), 'manuales': sum(r['origen'] == 'manual' for r in rows), 'automaticas': sum(r['origen'] == 'auto' for r in rows),
        'fichas_en_uniones_manuales': sum(r['fichas'] for r in rows if r['origen'] == 'manual'),
        'fichas_en_uniones_automaticas': sum(r['fichas'] for r in rows if r['origen'] == 'auto'),
        'merges_del_usuario': len(merges), 'merges_que_ya_no_estan_unidos': len(merges_huerfanos),
        'splits_del_usuario': len(splits), 'splits_que_chocan_con_una_union': len(split_vs_union), 'grupos_de_fichas_del_usuario': len(groups),
    }
    out = Path(a.out) if a.out else folder / 'informe-uniones'
    out.mkdir(parents=True, exist_ok=True)
    (out / 'INFORME.json').write_text(json.dumps(resumen, indent=2, ensure_ascii=False), encoding='utf8')
    with open(out / 'uniones.csv', 'w', newline='', encoding='utf8') as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(sorted(rows, key=lambda r: (r['origen'], -r['fichas'])))
    print(json.dumps(resumen, indent=2, ensure_ascii=False))
    print('Escrito en', out)


if __name__ == '__main__':
    main()
