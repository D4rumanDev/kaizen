"""Auditorías de Kaizen en una sola herramienta (todas de solo lectura: no escriben en la app ni en la TV).

Uso (desde la raíz del repo; sin `-I`, algunas necesitan Pillow o librerías del usuario):
    python tools/audit/audit.py                 lista los temas
    python tools/audit/audit.py TEMA [argumentos]
    python tools/audit/audit.py TEMA --help     el uso de ese tema

Las copias salen de la TV con `adb pull /sdcard/Android/data/app.kaizen/files/backups/.` (o `node tools/tv.js logs` para los registros).
Cada tema es un módulo de esta carpeta con su propia cabecera de uso; esto solo los reúne.
"""
import runpy
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

# tema -> (módulo, para qué sirve)
TEMAS = {
    'unions': ('unions.py', 'separa las uniones de series en manuales y automáticas (CARPETA_DE_COPIAS [--backup …] [--out …])'),
    'slugs': ('slugs.py', 'cuenta los marcadores de temporada, parte y versión de los slugs y los que el analizador no entiende (CARPETA_DE_COPIAS)'),
    'match': ('match.py', 'mide cuántas series se podrían emparejar con AniList, con una muestra (LIBRO.json.gz [--sample N])'),
    'pending': ('pending.py', 'por qué quedan fichas «por revisar» en AniList y cuántas resolvería el criterio del matcher (anilist-links.json.gz)'),
    'unmerge': ('unmerge.py', 'fichero que deshace uniones hechas por error comparando el libro de antes y el de ahora (--before … --after … --out …); se aplica con la acción ADB SPLIT'),
    'check-migration': ('check_migration.py', 'prueba con SQLite las migraciones de Room de user-db (7 -> 8) y cache-db (15 -> 16) contra los esquemas exportados'),
}


def main():
    # La consola de Windows no es UTF-8 por defecto: los textos llevan acentos y flechas.
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    if len(sys.argv) < 2 or sys.argv[1] in ('-h', '--help') or sys.argv[1] not in TEMAS:
        if len(sys.argv) >= 2 and sys.argv[1] not in ('-h', '--help'):
            print(f'tema desconocido: {sys.argv[1]}\n')
        print(__doc__)
        print('Temas:')
        for nombre, (_, detalle) in TEMAS.items():
            print(f'  {nombre:14s} {detalle}')
        sys.exit(0 if len(sys.argv) < 2 or sys.argv[1] in ('-h', '--help') else 2)
    modulo = HERE / TEMAS[sys.argv[1]][0]
    sys.argv = [str(modulo)] + sys.argv[2:]
    runpy.run_path(str(modulo), run_name='__main__')


if __name__ == '__main__':
    main()
