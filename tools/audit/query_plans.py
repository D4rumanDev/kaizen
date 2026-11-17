#!/usr/bin/env python3
"""Plan de ejecución (EXPLAIN QUERY PLAN) de cada @Query de Room, sobre los esquemas exportados (solo lectura).

  python tools/audit/query_plans.py [--all]

Crea en memoria las tablas y los índices de la última versión exportada de cada base (app/schemas) y pide a SQLite el plan de cada consulta de los DAO de app/src/main. Informa de las que
recorren una tabla entera (`SCAN`) o necesitan ordenar aparte (`USE TEMP B-TREE`); con --all lista también las que usan índice. No mide tiempos: sin datos no se sabe si compensa un índice;
sirve para saber dónde mirar con los datos reales (la copia de la TV). `SELECT *` también se anota: trae todas las columnas.
"""
import glob, json, os, re, sqlite3, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCHEMAS = os.path.join(ROOT, 'app', 'schemas')
SRC = os.path.join(ROOT, 'app', 'src', 'main', 'java')
QUERY = re.compile(r'@(?:get:)?Query\(\s*((?:"(?:[^"\x5c]|\x5c.)*"\s*\+?\s*)+)\)', re.S)
PIECE = re.compile(r'"((?:[^"\x5c]|\x5c.)*)"')


def build_db():
    con = sqlite3.connect(':memory:')
    for folder in sorted(glob.glob(os.path.join(SCHEMAS, '*'))):
        versions = sorted(glob.glob(os.path.join(folder, '*.json')), key=lambda p: int(os.path.splitext(os.path.basename(p))[0]))
        if not versions:
            continue
        db = json.load(open(versions[-1], encoding='utf-8'))['database']
        for entity in db['entities']:
            name = entity['tableName']
            con.execute(entity['createSql'].replace('${TABLE_NAME}', name))
            for index in entity.get('indices', []):
                con.execute(index['createSql'].replace('${TABLE_NAME}', name))
        print(f'esquema {os.path.basename(folder)} v{os.path.splitext(os.path.basename(versions[-1]))[0]}: {len(db["entities"])} tablas')
    return con


def queries():
    for d, _, fs in os.walk(SRC):
        for f in fs:
            if f.endswith('.kt'):
                text = open(os.path.join(d, f), encoding='utf-8', errors='ignore').read()
                for m in QUERY.finditer(text):
                    sql = ''.join(PIECE.findall(m.group(1)))
                    sql = sql.replace(chr(92) + '"', '"').replace(chr(92) + chr(36), chr(36))
                    yield os.path.relpath(os.path.join(d, f), ROOT).replace(os.sep, '/'), text.count(chr(10), 0, m.start()) + 1, ' '.join(sql.split())


def main():
    show_all = '--all' in sys.argv
    con = build_db()
    seen, scans, listings, sorts, stars, errors, ok = set(), [], [], [], [], [], 0
    for path, line, sql in queries():
        if (path, sql) in seen:
            continue
        seen.add((path, sql))
        bound = re.sub(r':\w+', '?', sql)
        try:
            plan = [row[3] for row in con.execute('EXPLAIN QUERY PLAN ' + bound, [None] * bound.count('?'))]
        except sqlite3.Error as e:
            errors.append((path, line, sql, str(e)))
            continue
        scan = [p for p in plan if p.startswith('SCAN')]
        temp = [p for p in plan if 'TEMP B-TREE' in p]
        if scan:
            # sin WHERE ni JOIN el recorrido completo es lo que se pide (un listado); con ellos hay filtro sin índice
            if re.search(r'(?i)where|join', bound):
                scans.append((path, line, sql, scan))
            else:
                listings.append((path, line, sql))
        if temp:
            sorts.append((path, line, sql, temp))
        if re.match(r'(?i)select \*', bound):
            stars.append((path, line, sql))
        if not scan and not temp:
            ok += 1
            if show_all:
                print(f'  ok {path}:{line}  {sql[:110]}  -> {plan[0] if plan else ""}')
    print(f'\n{len(seen)} consultas: {ok} con índice, {len(listings)} listados completos (sin filtro), {len(scans)} filtran sin índice, {len(sorts)} ordenan aparte, {len(stars)} con SELECT *, {len(errors)} que SQLite no pudo planificar')
    for title, rows in (('FILTRAN RECORRIENDO LA TABLA (WHERE/JOIN sin índice)', scans), ('ORDENAN CON UN ÁRBOL TEMPORAL', sorts)):
        print(f'\n## {title}')
        for path, line, sql, detail in rows:
            print(f'  {path}:{line}  {sql[:120]}\n      {" | ".join(detail)}')
    if errors:
        print('\n## SIN PLAN')
        for path, line, sql, e in errors:
            print(f'  {path}:{line}  {sql[:100]}  ({e})')


main()
