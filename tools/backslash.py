r"""Barra invertida sin sustos: escribir código con `\` cuando el paso por la consola (heredoc, comillas) se la come, y detectar los daños.

Uso (desde la raíz del repo):
    python tools/backslash.py put FICHERO            escribe en FICHERO lo que llega por la entrada estándar, cambiando el marcador {BS} por «\»
    python tools/backslash.py sub FICHERO            igual, pero cambia {BS} por «\» dentro de un fichero que ya existe (útil tras escribirlo con el marcador)
    python tools/backslash.py check [RUTA ...]       busca barras perdidas en .kt, .java, .py y .js (por defecto `app/src` y `tools`)
    python tools/backslash.py eol check|fix [RUTA ...]  finales de línea (CRLF/LF) cambiados respecto a HEAD: `check` los lista (código 1), `fix` los devuelve a los de HEAD si el
                                                     fichero es todo CRLF o todo LF (los mezclados solo se avisan). Sin RUTA: todos los modificados. Un script que escribe en modo
                                                     texto convierte CRLF en LF sin avisar; esto lo detecta (lo llama también `ship.js check` y `ship.js docs`).
    python tools/backslash.py eol hook               modo hook PostToolUse de Claude Code (.claude/settings.json): corrige el fichero editado (o, tras Bash, los modificados) y avisa
                                                     con additionalContext. Un fichero vacío `.eol-off` en la raíz lo desactiva (conversiones a propósito).

Cómo se usa: en el texto se escribe {BS} donde va la barra («'{BS}{BS}'» sale «'\\'», «"{BS}n"» sale «"\n"»); como el marcador no es una barra, ningún
intérprete lo toca. Si el texto necesita de verdad las letras {BS}, se escribe {{BS}}. `check` marca lo que suele salir mal al perder una barra:
un carácter de una sola barra entre comillas simples (Kotlin y Java no lo admiten: debe llevar dos).
"""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TEXTO = {'.md', '.json', '.kt', '.java', '.js', '.py', '.xml', '.gradle', '.txt', '.yml', '.yaml', '.sh', '.properties', '.ps1'}
MARCA = '{BS}'
LITERAL = '{{BS}}'
EXTENSIONES = {'.kt', '.java', '.py', '.js'}
# Un carácter de una barra sola escapa su propia comilla de cierre. No se buscan cadenas con una barra: «\"» dentro de una cadena es normal.
SOSPECHOSOS = [
    (re.compile(r"(?<![\\'])'\\'(?!')"), "carácter '\\' sin duplicar (debe ser '\\\\')"),
]


def expandir(texto):
    # {{BS}} es el marcador literal; se protege mientras se cambia el resto.
    return texto.replace(LITERAL, '\0').replace(MARCA, '\\').replace('\0', MARCA)


def leer_entrada():
    return sys.stdin.buffer.read().decode('utf-8')


def escribir(ruta, texto):
    # Sin traducir saltos de línea: lo que llega es lo que se guarda.
    with open(ruta, 'w', encoding='utf-8', newline='') as f:
        f.write(texto)


def revisar(rutas):
    problemas = 0
    for raiz in rutas:
        p = Path(raiz)
        ficheros = [p] if p.is_file() else [f for f in p.rglob('*') if f.suffix in EXTENSIONES and 'build' not in f.parts]
        for f in sorted(ficheros):
            if f.suffix not in EXTENSIONES or f.resolve() == Path(__file__).resolve():
                continue
            for n, linea in enumerate(f.read_text(encoding='utf-8', errors='replace').splitlines(), 1):
                for patron, motivo in SOSPECHOSOS:
                    if patron.search(linea):
                        problemas += 1
                        print(f'{f}:{n}: {motivo}\n    {linea.strip()[:160]}')
    print(f'{problemas} sospechosos' if problemas else 'sin barras sospechosas')
    return 1 if problemas else 0


def eol_tipo(datos):
    crlf = datos.count(b'\r\n')
    lf = datos.count(b'\n') - crlf
    if crlf and lf:
        return 'mixto'
    return 'crlf' if crlf else 'lf' if lf else 'ninguno'


def eol_git(*args):
    return subprocess.run(['git', '--literal-pathspecs', *args], cwd=ROOT, capture_output=True, stdin=subprocess.DEVNULL)


def eol_modificados():
    salida = eol_git('-c', 'core.quotepath=false', 'diff', '--name-only', 'HEAD').stdout.decode('utf-8', 'replace')
    return [linea for linea in salida.splitlines() if linea]


def eol_relativa(ruta):
    p = Path(ruta)
    p = p if p.is_absolute() else ROOT / p
    try:
        return p.resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return None


def eol_revisar(rel, corregir):
    """Devuelve (estado, texto) o None si no hay nada que decir."""
    if Path(rel).suffix.lower() not in TEXTO:
        return None
    fichero = ROOT / rel
    if not fichero.resolve().is_relative_to(ROOT):
        return None
    antes = eol_git('show', 'HEAD:' + rel)
    if antes.returncode != 0 or not fichero.is_file():
        return None
    ahora = fichero.read_bytes()
    t_antes, t_ahora = eol_tipo(antes.stdout), eol_tipo(ahora)
    if t_antes == t_ahora or 'ninguno' in (t_antes, t_ahora):
        return None
    if t_antes == 'mixto' or t_ahora == 'mixto':
        return ('mixto', f'{rel}: HEAD {t_antes}, ahora {t_ahora} (no se toca)')
    if corregir:
        sin = ahora.replace(b'\r\n', b'\n')
        temporal = fichero.with_name(fichero.name + '.eol-tmp')
        try:
            temporal.write_bytes(sin.replace(b'\n', b'\r\n') if t_antes == 'crlf' else sin)
            os.replace(temporal, fichero)
        finally:
            if temporal.exists():
                temporal.unlink()
        return ('corregido', f'{rel}: {t_ahora} -> {t_antes}')
    return ('cambiado', f'{rel}: HEAD {t_antes}, ahora {t_ahora}')


def eol_main(args):
    # En modo hook nada debe romper la herramienta que lo dispara: cualquier fallo sale con 0.
    if args[:1] == ['hook']:
        try:
            return eol_dispatch(args)
        except Exception:
            return 0
    return eol_dispatch(args)


def eol_dispatch(args):
    orden = args[0] if args else ''
    if orden == 'hook':
        if (ROOT / '.eol-off').exists():
            return 0
        try:
            evento = json.load(sys.stdin)
        except ValueError:
            return 0
        ruta = (evento.get('tool_input') or {}).get('file_path')
        rels = [eol_relativa(ruta)] if ruta else eol_modificados()
        hallazgos = [h for h in (eol_revisar(r, True) for r in rels if r) if h]
        if hallazgos:
            texto = 'backslash.py eol: ' + '; '.join(f'{estado} {detalle}' for estado, detalle in hallazgos)
            print(json.dumps({'hookSpecificOutput': {'hookEventName': 'PostToolUse', 'additionalContext': texto}}))
        return 0
    if orden not in ('check', 'fix'):
        print(__doc__)
        return 2
    rels = [eol_relativa(r) for r in args[1:]] or eol_modificados()
    hallazgos = [h for h in (eol_revisar(r, orden == 'fix') for r in rels if r) if h]
    for estado, detalle in hallazgos:
        print(f'{estado}: {detalle}')
    if not hallazgos:
        print('finales de línea sin cambios respecto a HEAD')
    return 1 if orden == 'check' and hallazgos else 0


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    if len(sys.argv) < 2 or sys.argv[1] not in ('put', 'sub', 'check', 'eol'):
        print(__doc__)
        return 0 if len(sys.argv) < 2 or sys.argv[1] in ('-h', '--help') else 2
    modo = sys.argv[1]
    if modo == 'eol':
        return eol_main(sys.argv[2:])
    if modo == 'check':
        return revisar(sys.argv[2:] or ['app/src', 'tools'])
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    ruta = sys.argv[2]
    if modo == 'put':
        escribir(ruta, expandir(leer_entrada()))
    else:
        original = Path(ruta).read_bytes().decode('utf-8')
        escribir(ruta, expandir(original))
    print(f'escrito {ruta}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
