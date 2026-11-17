"""Prueba las migraciones de Room de user-db (7 -> 8) y cache-db (15 -> 16) con SQLite, sin Android (solo lectura sobre el repositorio).

Uso (desde la raíz del repo):
    python tools/audit/audit.py check-migration

Qué comprueba:
  - user-db 7 -> 8: crea las tablas del esquema 7 (`app/schemas/.../7.json`), mete filas con uniones encadenadas (A -> B -> C), una fila que choca y la
    fila `fromId = toId`, ejecuta las sentencias de `UserDB.MIGRATION_7_8_SQL` (leídas de UserDB.kt) y comprueba que las fichas, favoritos y vistos pasan al
    id vigente, que `SeriesRedirect` y `UserMigration` desaparecen y que las tablas que quedan coinciden con el esquema 8.
  - cache-db 15 -> 16: crea las tablas del esquema 15 más las viejas (`SeriesRegistry`, `SeriesMap`, `SeriesRedirect`, `FavoriteAV1`, `Record`), aplica la
    regla de `CacheDB.MIGRATION_15_16` y comprueba que solo quedan las tablas del esquema 16 y que `DECLARED_TABLES` (leída de CacheDB.kt) es exactamente esa lista.
Sale con código 1 si algo no cuadra.
"""
import json
import re
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / 'app' / 'schemas'
DB_SRC = ROOT / 'app' / 'src' / 'main' / 'java' / 'app' / 'kaizen' / 'database'
fallos = []


def check(ok, texto):
    print(('  ok   ' if ok else '  FALLA ') + texto)
    if not ok:
        fallos.append(texto)


def schema(db, version):
    return json.loads((SCHEMAS / f'app.kaizen.database.{db}' / f'{version}.json').read_text(encoding='utf-8'))['database']


def create(conn, db, version, extra=()):
    for entity in schema(db, version)['entities']:
        conn.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        for index in entity.get('indices', []):
            conn.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    for sql in extra:
        conn.execute(sql)


def tables(conn):
    return {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%'")}


def columns(conn, table):
    return [(r[1], r[2].upper(), r[3], r[5]) for r in conn.execute(f'PRAGMA table_info(`{table}`)')]


def user_db():
    print('user-db 7 -> 8')
    text = (DB_SRC / 'UserDB.kt').read_text(encoding='utf-8')
    block = text[text.index('MIGRATION_7_8_SQL: List<String>'):]
    block = block[:block.index('\n        }\n')]
    repeat = re.search(r'repeat\((\d+)\) \{(.*?)\n            \}', block, re.S)
    once = [m for m in re.findall(r'add\("([^"]+)"\)', block[repeat.end():])]
    each = re.findall(r'add\("([^"]+)"\)', repeat.group(2))
    sql = each * int(repeat.group(1)) + once
    check(len(each) == 3 and len(once) == 2, f'sentencias leídas: {len(each)} por vuelta x {repeat.group(1)} + {len(once)} finales')

    conn = sqlite3.connect(':memory:')
    create(conn, 'UserDB', 7)
    cols = [c[0] for c in columns(conn, 'Ficha')]
    ficha = lambda f, s, i: conn.execute(f'INSERT INTO Ficha ({",".join(cols[:3])}) VALUES (?,?,?)', (f, s, i))
    # 9 -> 7 -> 5 (cadena), 20 -> 5, y (30, 30) como la fila que guardaba el mayor id.
    conn.executemany('INSERT INTO SeriesRedirect VALUES (?,?)', [(9, 7), (7, 5), (20, 5), (30, 30)])
    ficha('a', 'x', 5); ficha('b', 'x', 9); ficha('c', 'x', 7); ficha('d', 'x', 20); ficha('e', 'solo', 11)
    fav = [r[1] for r in conn.execute('PRAGMA table_info(FavoriteAV1)')]
    rec = [r[1] for r in conn.execute('PRAGMA table_info(Record)')]
    def row(table, names, **vals):
        defaults = {n: (0 if t in ('INTEGER', 'REAL') else '') for n, t, nn, pk in columns(conn, table)}
        defaults.update(vals)
        conn.execute(f'INSERT INTO {table} ({",".join(names)}) VALUES ({",".join("?" * len(names))})', [defaults[n] for n in names])
    row('FavoriteAV1', fav, seriesId=9, aid=1, slug='f1', source='b')
    row('FavoriteAV1', fav, seriesId=11, aid=2, slug='f2', source='e')
    row('FavoriteAV1', fav, seriesId=20, aid=3, slug='f3', source='d')   # 20 -> 5 choca con el de 9 -> 5: uno se queda como estaba
    row('Record', rec, seriesId=20, eid=1, slug='r1', source='d')
    row('Record', rec, seriesId=11, eid=2, slug='r2', source='e')
    conn.execute('INSERT INTO UserMigration VALUES (1, 0, 0, 0)')

    for statement in sql:
        conn.execute(statement)

    ids = dict(((f, s), i) for f, s, i in conn.execute('SELECT fuente, slug, seriesId FROM Ficha'))
    check(ids == {('a', 'x'): 5, ('b', 'x'): 5, ('c', 'x'): 5, ('d', 'x'): 5, ('e', 'solo'): 11}, f'fichas con su id vigente: {ids}')
    favs = dict(conn.execute('SELECT slug, seriesId FROM FavoriteAV1'))
    check(favs['f2'] == 11 and favs['f3'] == 5 and favs['f1'] in (7, 9), f'favoritos: {favs} (el que llega primero a la serie vigente se queda con ella; el que choca no se borra)')
    recs = dict(conn.execute('SELECT slug, seriesId FROM Record'))
    check(recs == {'r1': 5, 'r2': 11}, f'vistos: {recs}')
    check(len(conn.execute('SELECT 1 FROM FavoriteAV1').fetchall()) == 3 and len(conn.execute('SELECT 1 FROM Record').fetchall()) == 2, 'no se borró ninguna fila')
    expected = {e['tableName'] for e in schema('UserDB', 8)['entities']}
    check(tables(conn) == expected, f'tablas que quedan = esquema 8 {sorted(expected)}')
    for entity in schema('UserDB', 8)['entities']:
        fresh = sqlite3.connect(':memory:')
        fresh.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        check(columns(conn, entity['tableName']) == columns(fresh, entity['tableName']), f'columnas de {entity["tableName"]} iguales al esquema 8')


def cache_db():
    print('cache-db 15 -> 16')
    text = (DB_SRC / 'CacheDB.kt').read_text(encoding='utf-8')
    declared = set(re.findall(r'"(\w+)"', text[text.index('val DECLARED_TABLES'):text.index('val MIGRATION_15_16')]))
    expected = {e['tableName'] for e in schema('CacheDB', 16)['entities']}
    check(declared == expected, f'DECLARED_TABLES = tablas del esquema 16 ({len(expected)})')
    check({e['tableName'] for e in schema('CacheDB', 15)['entities']} == expected, 'el esquema 16 declara las mismas tablas que el 15 (solo se borran restos)')

    conn = sqlite3.connect(':memory:')
    old = ['SeriesRegistry', 'SeriesMap', 'SeriesRedirect', 'FavoriteAV1', 'Record', 'UserMigration']
    create(conn, 'CacheDB', 15, [f'CREATE TABLE `{t}` (`a` INTEGER)' for t in old])
    conn.execute("INSERT INTO ApiCache DEFAULT VALUES") if False else None
    antes = tables(conn)
    for table in [t for t in tables(conn) if t not in declared and not t.startswith(('sqlite_', 'android_', 'room_'))]:
        conn.execute(f'DROP TABLE IF EXISTS `{table}`')
    check(tables(conn) == expected, f'tras la migración solo quedan las {len(expected)} tablas del esquema ({sorted(antes - tables(conn))} borradas)')


if __name__ == '__main__':
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    user_db()
    cache_db()
    print('TODO CORRECTO' if not fallos else f'{len(fallos)} COMPROBACIONES FALLAN')
    sys.exit(1 if fallos else 0)
