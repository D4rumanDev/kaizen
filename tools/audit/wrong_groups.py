"""Lista los grupos de fichas del libro de series que juntan animes distintos de AniList (solo lectura).

Uso:  python tools/audit/audit.py groups CARPETA_DE_COPIAS [--backup kaizen-backup-AAAAMMDD-HHMMSS.json] [--out informe]

CARPETA_DE_COPIAS es la de `backups/` de la app en la TV bajada con `adb pull`, con `anilist-links.json.gz` y las `kaizen-backup-*.json`.
Sin --backup se usa la más reciente.

Un grupo de la copia (`groups`) son fichas que el libro de series tiene por la misma serie. Aquí se parte cada grupo por el anime de
AniList de su enlace aplicado; si hay más de uno, el grupo es sospechoso. Las fichas sin enlace aplicado no se mueven.

Clasificación (heurística, a revisar a ojo):
  - «distinta»:  ningún par de partes comparte el primer trozo del slug (Dragon Ball Z con High School DxD).
  - «franquicia»: alguna pareja de partes lo comparte (Black Clover y Black Clover 2nd Season): puede ser una temporada que AniList
    trata como entrada propia; separarla o no es decisión del usuario.

Escribe en --out (por defecto, CARPETA/informe-grupos):
  informe.md            tabla de grupos, partes (anime de AniList, nº de fichas, slugs de muestra) y clasificación.
  split-distintas.json  para la acción ADB `SPLIT` de los grupos «distinta»: cada parte menos la mayor pasa a una serie propia.
  split-franquicias.json lo mismo para los «franquicia».
No toca la TV ni la app: los JSON hay que copiarlos a la carpeta de copias de la TV para aplicarlos, y eso es un cambio que valida el usuario.
"""
import argparse
import gzip
import json
from collections import defaultdict
from pathlib import Path


def first_token(slug):
    return slug.split('-')[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('folder')
    ap.add_argument('--backup')
    ap.add_argument('--out')
    ap.add_argument('--separar', help='decisión del usuario: ids de AniList a sacar, separados por comas; con «+» van juntos en una sola serie nueva (p. ej. 8769+13659,152137). Escribe split-depurado.json')
    a = ap.parse_args()
    folder = Path(a.folder)
    # --backup puede ser un nombre dentro de la carpeta de copias o una ruta.
    backup_file = (folder / a.backup if (folder / a.backup).exists() else Path(a.backup)) if a.backup else max(folder.glob('kaizen-backup-*.json'), key=lambda p: p.name)
    groups = json.loads(backup_file.read_text(encoding='utf8')).get('groups', [])
    links = json.load(gzip.open(folder / 'anilist-links.json.gz', 'rt', encoding='utf8'))['links']
    applied = {}
    for link in links:
        source = link.get('fuente') or link.get('sourceId')
        if link.get('estado') == 'aplicado' and link.get('anilistId') and source:
            applied[(source, link['slug'])] = link['anilistId']

    found = []
    for group in groups:
        parts = defaultdict(list)
        for ficha in group:
            source, slug = ficha.split(':', 1)
            anilist = applied.get((source, slug))
            if anilist:
                parts[anilist].append([source, slug])
        if len(parts) > 1:
            ordered = sorted(parts.items(), key=lambda kv: (-len(kv[1]), kv[0]))
            tokens = [{first_token(s) for _, s in fichas if len(first_token(s)) >= 4} for _, fichas in ordered]
            related = any(tokens[i] & tokens[j] for i in range(len(tokens)) for j in range(i + 1, len(tokens)))
            found.append({'fichas': len(group), 'partes': ordered, 'clase': 'franquicia' if related else 'distinta'})

    out = Path(a.out) if a.out else folder / 'informe-grupos'
    out.mkdir(parents=True, exist_ok=True)
    lines = [f'# Grupos con animes de AniList distintos', '',
             f'Copia: `{backup_file.name}` · grupos en la copia: {len(groups)} · sospechosos: {len(found)} '
             f'({sum(f["clase"] == "distinta" for f in found)} «distinta», {sum(f["clase"] == "franquicia" for f in found)} «franquicia»)', '',
             'La parte mayor se queda donde está; en el JSON de `SPLIT` van las demás. La clasificación es una heurística.', '']
    for number, item in enumerate(sorted(found, key=lambda f: (f['clase'], -f['fichas'])), 1):
        lines.append(f'## {number}. {item["clase"]} · {item["fichas"]} fichas')
        for index, (anilist, fichas) in enumerate(item['partes']):
            sample = ', '.join(sorted({s for _, s in fichas})[:4])
            lines.append(f'- {"se queda" if index == 0 else "se separa"}: AniList {anilist} · {len(fichas)} fichas · {sample}')
        lines.append('')
    (out / 'informe.md').write_text('\n'.join(lines), encoding='utf8')
    for clase, name in (('distinta', 'split-distintas.json'), ('franquicia', 'split-franquicias.json')):
        moves = [fichas for item in found if item['clase'] == clase for _, fichas in item['partes'][1:]]
        (out / name).write_text(json.dumps({'groups': moves}, ensure_ascii=False, indent=1), encoding='utf8')
        print(f'{name}: {len(moves)} grupos a separar')
    if a.separar:
        by_anilist = {anilist: fichas for item in found for anilist, fichas in item['partes']}
        wanted = [[int(x) for x in chunk.split('+')] for chunk in a.separar.split(',') if chunk.strip()]
        missing = [x for chunk in wanted for x in chunk if x not in by_anilist]
        stays = {item['partes'][0][0] for item in found}
        if missing:
            raise SystemExit(f'ids de AniList que no están en ningún grupo sospechoso: {missing}')
        if any(x in stays for chunk in wanted for x in chunk):
            raise SystemExit('se pide sacar la parte que se queda de algún grupo: se quedaría el grupo sin su parte mayor')
        moves = [[f for x in chunk for f in by_anilist[x]] for chunk in wanted]
        (out / 'split-depurado.json').write_text(json.dumps({'groups': moves}, ensure_ascii=False, indent=1), encoding='utf8')
        print(f'split-depurado.json: {len(moves)} grupos a separar, {sum(len(m) for m in moves)} fichas')
    print(f'{len(found)} grupos sospechosos de {len(groups)}. Escrito en {out}')


if __name__ == '__main__':
    main()
