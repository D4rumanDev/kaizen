#!/usr/bin/env python3
"""Kotlin source reference graph backed by Kotlin PSI.

Commands: stats, dead, who, deps, html, dot, unused, prune.
The graph is conservative: unresolved/ambiguous name references are connected to
all plausible declarations, preferring same-file and same-package candidates.
PSI parsing is syntactic; this tool does not claim compiler-grade symbol resolution.
"""
from __future__ import annotations
import argparse, collections, re, datetime as dt, html, json, os, pathlib, shutil, subprocess, sys, tempfile
from dataclasses import dataclass, field
from typing import Any

VERSION = "2.0.0"
HERE = pathlib.Path(__file__).resolve().parent
HELPER = HERE / "kotlin-psi"
DEFAULT_OUT = pathlib.Path("backups/code-graph")

@dataclass
class Node:
    id: str
    name: str
    kind: str
    fqname: str
    file: str
    line: int
    end_line: int
    start: int
    end: int
    visibility: str
    modifiers: set[str]
    annotations: set[str]
    parent_id: str | None
    signature: str
    refs: set[str] = field(default_factory=set)
    targets: set[str] = field(default_factory=set)
    root_reasons: set[str] = field(default_factory=set)
    status: str = "muerto"
    local: bool = False

    def label(self) -> str:
        return f"{self.file}:{self.line}-{self.end_line} {self.kind} {self.name}"

class Analyzer:
    def __init__(self, root: pathlib.Path):
        self.root = root.resolve()
        self.main_root = self.root / "app/src/main"
        self.test_roots = [self.root / "app/src"]
        self.raw: dict[str, dict[str, Any]] = {}
        self.nodes: list[Node] = []
        self.by_id: dict[str, Node] = {}
        self.by_name: dict[str, list[Node]] = collections.defaultdict(list)
        self.test_refs: dict[str, set[str]] = collections.defaultdict(set)
        self.file_errors: dict[str, list[str]] = {}
        self.imports: dict[str, list[dict[str, Any]]] = {}
        self._run_psi()
        self._build()

    def _run_psi(self) -> None:
        if not self.main_root.is_dir():
            raise RuntimeError(f"No existe {self.main_root}; ejecuta desde la raíz del proyecto Android.")
        wrapper_unix = self.root / "gradlew"
        wrapper_windows = self.root / "gradlew.bat"
        if os.name == "nt" and wrapper_windows.is_file():
            gradle_command = ["cmd", "/c", str(wrapper_windows)]
        elif wrapper_unix.is_file():
            gradle_command = [str(wrapper_unix)]
        elif wrapper_windows.is_file():
            gradle_command = ["cmd", "/c", str(wrapper_windows)]
        elif shutil.which("gradle"):
            gradle_command = [shutil.which("gradle")]
        else:
            raise RuntimeError("No encuentro Gradle. Instala Gradle o incluye gradlew/gradlew.bat en la raíz del proyecto.")
        fd, tmp = tempfile.mkstemp(prefix="code-graph-", suffix=".json")
        os.close(fd)
        try:
            cmd = gradle_command + ["-q", "-p", str(HELPER), "analyze", f"-PprojectRoot={self.root}", f"-PoutputFile={tmp}"]
            proc = subprocess.run(cmd, cwd=self.root, text=True, capture_output=True)
            if proc.returncode:
                detail = (proc.stderr or proc.stdout).strip()
                raise RuntimeError("No se pudo ejecutar el analizador Kotlin PSI.\n" + detail[-6000:])
            with open(tmp, encoding="utf-8") as f:
                data = json.load(f)
            self.raw = {x["path"]: x for x in data.get("files", [])}
            for item in data.get("files", []):
                if item.get("syntaxErrors"):
                    self.file_errors[item["path"]] = item["syntaxErrors"]
                self.imports[item["path"]] = item.get("imports", [])
        finally:
            pathlib.Path(tmp).unlink(missing_ok=True)

    def _build(self) -> None:
        # First pass: create stable PSI-identity nodes. Local declarations are kept
        # in the PSI output but excluded from the global symbol graph.
        for path, file_data in self.raw.items():
            if self._is_test_source(path):
                continue
            decls = file_data.get("declarations", [])
            by_decl_id = {d["id"]: d for d in decls}
            def is_local(decl):
                parent = by_decl_id.get(decl.get("parentId"))
                return decl.get("kind") == "KtFunctionLiteral" or bool(parent and parent.get("kind") in ("fun", "property", "constructor", "KtFunctionLiteral"))
            # Las referencias de lo local (lambdas y funciones internas) cuentan para la declaración global que lo contiene.
            local_refs: dict[str, list] = collections.defaultdict(list)
            for d in decls:
                if is_local(d):
                    owner = by_decl_id.get(d.get("parentId"))
                    while owner is not None and is_local(owner):
                        owner = by_decl_id.get(owner.get("parentId"))
                    if owner is not None:
                        local_refs[owner["id"]].extend(d.get("refs", []))
            for d in decls:
                if is_local(d):
                    continue
                d = dict(d, refs=list(d.get("refs", [])) + local_refs.get(d["id"], []))
                file_data.setdefault("_folded", {})[d["id"]] = d["refs"]
                n = Node(d["id"], d["name"], d["kind"], d.get("fqName", d["name"]), d["file"],
                         int(d["line"]), int(d["endLine"]), int(d["startOffset"]), int(d["endOffset"]),
                         d.get("visibility", "public"), set(d.get("modifiers", [])), set(d.get("annotations", [])),
                         d.get("parentId"), d.get("signature", ""))
                n.refs = {r["name"] for r in d.get("refs", [])}
                self.nodes.append(n); self.by_id[n.id] = n; self.by_name[n.name].append(n)
        # Resolve conservatively by name: a reference reaches every declaration with that name except private ones of other files (the PSI has no types, so a member call cannot be told from another class's member of the same name).
        package_by_file = {p: d.get("packageName", "") for p, d in self.raw.items()}
        for path, file_data in self.raw.items():
            decl_by_id = {d["id"]: d for d in file_data.get("declarations", [])}
            for d in file_data.get("declarations", []):
                src = self.by_id.get(d["id"])
                if not src:
                    continue
                import_names = {i["fqName"].split(".")[-1]: i["fqName"] for i in file_data.get("imports", []) if not i.get("allUnder")}
                for ref in file_data.get("_folded", {}).get(d["id"], d.get("refs", [])):
                    name = ref["name"]
                    candidates = self.by_name.get(name, [])
                    if not candidates:
                        continue
                    same_file = [x for x in candidates if x.file == path]
                    same_package = [x for x in candidates if package_by_file.get(x.file) == file_data.get("packageName")]
                    imported = [x for x in candidates if x.fqname == import_names.get(name)]
                    chosen = [x for x in candidates if x.visibility != "private" or x.file == path]
                    # Keep overloads and same-scope duplicate names; ambiguity is safer than a false dead result.
                    for target in chosen:
                        if target.id != src.id:
                            src.targets.add(target.id)
        self._mark_roots()
        self._mark_tests()
        live = {n.id for n in self.nodes if n.root_reasons}
        queue = collections.deque(live)
        while queue:
            current = self.by_id[queue.popleft()]
            targets = set(current.targets)
            if current.parent_id in self.by_id:
                targets.add(current.parent_id)
            for target in targets:
                if target not in live:
                    live.add(target); queue.append(target)
        for n in self.nodes:
            if n.id in live:
                n.status = "vivo"
            elif self._used_by_tests(n):
                n.status = "solo-pruebas"
            else:
                n.status = "muerto"

    def _mark_roots(self) -> None:
        keep_file = HERE / "code_graph_keep.txt"
        keep = set()
        if keep_file.exists():
            keep = {x.split("#", 1)[0].strip() for x in keep_file.read_text(encoding="utf-8").splitlines() if x.split("#", 1)[0].strip()}
        import re
        source_base = self.root / "app/src"
        resource_text = "\n".join(self._read_non_kt(source_base, {"xml", "java", "json", "html", "js", "txt"}))
        xml_text = "\n".join(self._read_non_kt(source_base, {"xml"}))
        java_text = "\n".join(self._read_non_kt(source_base, {"java"}))
        resource_tokens = set(re.findall(r"[A-Za-z_][A-Za-z0-9_]*", resource_text))
        java_tokens = set(re.findall(r"[A-Za-z_][A-Za-z0-9_]*", java_text))
        xml_callbacks = set(re.findall(r"(?:android:)?onClick\s*=\s*['\"]([A-Za-z_][A-Za-z0-9_]*)['\"]", xml_text))
        important_annotations = {"Keep", "Preview", "TypeConverter", "JavascriptInterface", "JvmStatic", "Composable", "Inject", "Provides", "Binds", "OnLifecycleEvent", "Subscribe", "Entity", "Dao", "Database", "Serializable", "SerialName", "Parcelize"}
        important_modifiers = {"override", "operator", "external", "expect", "actual"}
        for n in self.nodes:
            if n.name in keep or n.fqname in keep:
                n.root_reasons.add("keep-config")
            matched = n.annotations & important_annotations
            if matched:
                n.root_reasons.add("annotation:" + ",".join(sorted(matched)))
            matched_mods = n.modifiers & important_modifiers
            if matched_mods:
                n.root_reasons.add("modifier:" + ",".join(sorted(matched_mods)))
            if n.name in {"main", "CREATOR"}:
                n.root_reasons.add("conventional-entry-point")
            if n.name in resource_tokens and n.kind in {"class", "object", "interface", "enum"}:
                n.root_reasons.add("xml-class-reference")
            if n.name in xml_callbacks:
                n.root_reasons.add("xml-onClick-callback")
            if n.name in java_tokens:
                n.root_reasons.add("java-source-reference-by-name")
        # AndroidManifest component names, fully-qualified and relative names.
        manifest = self.main_root / "AndroidManifest.xml"
        if manifest.exists():
            text = manifest.read_text(encoding="utf-8", errors="replace")
            for n in self.nodes:
                if n.kind in {"class", "object"} and (n.fqname in text or ("." + n.name) in text or n.name in text):
                    n.root_reasons.add("manifest")

    @staticmethod
    def _is_test_source(relative_path: str) -> bool:
        parts = relative_path.replace("\\", "/").split("/")
        return len(parts) > 2 and "test" in parts[2].lower()

    def _mark_tests(self) -> None:
        import re
        base = self.root / "app/src"
        for path in (base.rglob("*.kt") if base.is_dir() else []):
            rel = path.relative_to(self.root).as_posix()
            if not self._is_test_source(rel):
                continue
            try:
                text = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            tokens = set(re.findall(r"[A-Za-z_][A-Za-z0-9_]*", text))
            for token in tokens:
                if token in self.by_name:
                    self.test_refs[token].add(rel)

    def _used_by_tests(self, n: Node) -> bool:
        # Conservative name-based test check; same-name declarations are ambiguous.
        return bool(self.test_refs.get(n.name))

    @staticmethod
    def _read_non_kt(base: pathlib.Path, extensions: set[str]) -> list[str]:
        out = []
        if not base.is_dir(): return out
        for p in base.rglob("*"):
            if p.is_file() and p.suffix.lstrip(".").lower() in extensions:
                try:
                    relative = p.relative_to(base.parent.parent).as_posix() if base.name == "src" else ""
                    if relative and Analyzer._is_test_source(relative):
                        continue
                    out.append(p.read_text(encoding="utf-8", errors="replace"))
                except OSError: pass
        return out

    def filtered(self, prefixes: list[str] | None = None) -> list[Node]:
        if not prefixes: return self.nodes
        norm = [x.replace("\\", "/").rstrip("/") for x in prefixes]
        return [n for n in self.nodes if any(n.file == p or n.file.startswith(p + "/") for p in norm)]

    def callers(self, n: Node) -> list[Node]:
        return [x for x in self.nodes if n.id in x.targets]

    def summary(self) -> dict[str, int]:
        c = collections.Counter(n.status for n in self.nodes)
        return {k: c[k] for k in ("vivo", "solo-pruebas", "muerto")}


def cmd_stats(a: Analyzer, _args: argparse.Namespace) -> None:
    c = a.summary()
    print(f"{len(a.nodes)} declaraciones en {len({n.file for n in a.nodes})} ficheros; vivos {c['vivo']}, solo-pruebas {c['solo-pruebas']}, muertos {c['muerto']}")
    if a.file_errors:
        print(f"AVISO: {len(a.file_errors)} ficheros contienen errores sintácticos PSI; no uses prune --apply hasta corregirlos.")

def cmd_dead(a: Analyzer, args: argparse.Namespace) -> None:
    status = "solo-pruebas" if args.tests else "muerto"
    selected = [n for n in a.filtered(args.paths) if n.status == status and not (n.parent_id in a.by_id and a.by_id[n.parent_id].status == status)]
    print(f"## {status}: {len(selected)} candidatos (contenedores agrupados)")
    for n in sorted(selected, key=lambda x: (x.file, x.line)):
        print(f"{n.label()} [{n.visibility}]" + (f" roots={','.join(sorted(n.root_reasons))}" if n.root_reasons else ""))

def cmd_lookup(a: Analyzer, args: argparse.Namespace) -> None:
    matches = a.by_name.get(args.name, [])
    if not matches:
        print(f"No hay declaraciones llamadas {args.name}"); return
    for n in matches:
        print(f"{n.label()} [{n.status}] id={n.id}")
        if args.command == "who":
            for caller in a.callers(n): print(f"  <- {caller.label()} [{caller.status}]")
            for f in sorted(a.test_refs.get(n.name, ())): print(f"  <- (prueba ambigua por nombre) {f}")
        else:
            for target_id in sorted(n.targets):
                target = a.by_id[target_id]
                print(f"  -> {target.label()} [{target.status}]")

def cmd_dot(a: Analyzer, args: argparse.Namespace) -> None:
    start = a.by_name.get(args.name, [])
    if not start: raise RuntimeError(f"No hay declaraciones llamadas {args.name}")
    seen: set[str] = set(); edges: set[tuple[str,str]] = set(); frontier = list(start)
    for _ in range(max(1, args.depth)):
        nxt = []
        for n in frontier:
            if n.id in seen: continue
            seen.add(n.id)
            for target in n.targets:
                edges.add((n.id, target)); nxt.append(a.by_id[target])
            for caller in a.callers(n):
                edges.add((caller.id, n.id)); nxt.append(caller)
        frontier = nxt
    colors = {"vivo":"palegreen", "muerto":"salmon", "solo-pruebas":"khaki"}
    print('digraph G { rankdir=LR; node [shape=box, style=filled, fontsize=10];')
    ids = {x for edge in edges for x in edge} | {n.id for n in start}
    for ident in sorted(ids):
        n = a.by_id[ident]
        label = f"{n.name}\\n{n.file}:{n.line}".replace('"', '\\"')
        print(f'  "{ident}" [label="{label}", fillcolor={colors[n.status]}];')
    for x,y in sorted(edges): print(f'  "{x}" -> "{y}";')
    print('}')

def cmd_html(a: Analyzer, args: argparse.Namespace) -> None:
    out = pathlib.Path(args.output) if args.output else a.root / DEFAULT_OUT / "index.html"
    if not out.is_absolute(): out = a.root / out
    rows = []
    for n in a.nodes:
        rows.append({"id":n.id,"name":n.name,"kind":n.kind,"file":n.file,"line":n.line,"endLine":n.end_line,"status":n.status,
                     "visibility":n.visibility,"uses":sorted(n.targets),"usedBy":[x.id for x in a.callers(n)],
                     "tests":sorted(a.test_refs.get(n.name, ())),"roots":sorted(n.root_reasons),"signature":n.signature})
    payload = json.dumps(rows, ensure_ascii=False).replace("</", "<\\/").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    page = HTML_PAGE.replace("__DATA__", payload)
    out.parent.mkdir(parents=True, exist_ok=True); out.write_text(page, encoding="utf-8")
    print(f"{out} ({len(rows)} nodos)")

HTML_PAGE = r'''<!doctype html><html lang="es"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Grafo Kotlin</title>
<style>body{font:14px system-ui;margin:20px;color:#202124}input,select{font:inherit;padding:7px;margin:0 8px 12px 0}table{border-collapse:collapse;width:100%}td,th{border-bottom:1px solid #ddd;padding:7px;text-align:left;vertical-align:top}.vivo{background:#eaf7ea}.muerto{background:#fde9e7}.solo-pruebas{background:#fff6d9}button{cursor:pointer}pre{white-space:pre-wrap}</style>
<h2>Grafo de código Kotlin</h2><input id="q" placeholder="Buscar símbolo o fichero" size="36"><select id="s"><option value="">Todos los estados</option><option>muerto</option><option>solo-pruebas</option><option>vivo</option></select><span id="count"></span>
<table><thead><tr><th>Estado</th><th>Tipo</th><th>Símbolo</th><th>Ubicación</th><th>Usa</th><th>Usado por</th></tr></thead><tbody id="body"></tbody></table><script>
const DATA=__DATA__;const byId=new Map(DATA.map(x=>[x.id,x]));const $=id=>document.getElementById(id);const opened=new Set();
function el(tag,txt,cls){const x=document.createElement(tag);if(txt!==undefined)x.textContent=txt;if(cls)x.className=cls;return x}
function render(){const q=$('q').value.toLocaleLowerCase(),s=$('s').value;const body=$('body');body.replaceChildren();let count=0;for(const n of DATA){if(s&&n.status!==s)continue;if(q&&!(n.name.toLocaleLowerCase().includes(q)||n.file.toLocaleLowerCase().includes(q)))continue;if(++count>1200)break;const tr=el('tr',undefined,n.status);for(const v of [n.status,n.kind,n.name,`${n.file}:${n.line}-${n.endLine}`,n.uses.length,n.usedBy.length+n.tests.length])tr.append(el('td',String(v)));tr.title=n.signature;tr.addEventListener('click',()=>{opened.has(n.id)?opened.delete(n.id):opened.add(n.id);render()});body.append(tr);if(opened.has(n.id)){const detail=el('tr'),td=el('td');td.colSpan=6;for(const [title,ids] of [['Usa',n.uses],['Usado por',n.usedBy]]){td.append(el('b',title));for(const id of ids){const target=byId.get(id);if(!target)continue;const b=el('button',`${target.name} — ${target.file}:${target.line}`);b.addEventListener('click',ev=>{ev.stopPropagation();$('q').value=target.name;opened.add(target.id);render()});td.append(b)}}if(n.tests.length){td.append(el('b','Referencias en pruebas'));for(const f of n.tests)td.append(el('div',f))}if(n.roots.length)td.append(el('pre',`Raíces: ${n.roots.join(', ')}`));detail.append(td);body.append(detail)}}$('count').textContent=` ${count} nodos${count>1200?' (máximo mostrado: 1200)':''}`}
$('q').addEventListener('input',render);$('s').addEventListener('change',render);render();</script></html>'''

def backup_files(root: pathlib.Path, files: set[pathlib.Path], label: str) -> pathlib.Path:
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    dest = root / "backups/code-graph" / f"{label}-{stamp}"
    for p in sorted(files):
        rel = p.relative_to(root); target = dest / rel; target.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(p, target)
    return dest

# Importaciones que Kotlin usa sin nombrarlas en el texto (delegados `by`, `for`, operadores, desestructuración): nunca se consideran sin uso.
IMPLICIT_IMPORTS = {"getValue", "setValue", "provideDelegate", "iterator", "invoke", "plus", "minus", "times", "div", "rem", "unaryMinus", "compareTo", "contains", "get", "set", "next", "hasNext", "rangeTo", "not"}

def cmd_unused(a: Analyzer, args: argparse.Namespace) -> None:
    selected = set(args.paths or [])
    unused: list[tuple[str,int,str]] = []
    for path, imports in a.imports.items():
        if selected and not any(path == x or path.startswith(x.rstrip("/") + "/") for x in selected): continue
        raw_file = a.root / path
        try: content = raw_file.read_bytes().decode("utf-8")
        except OSError: continue
        # PSI references exclude import directives; count source-level identifier mentions.
        references = set(a.raw.get(path, {}).get("references", []))
        for item in imports:
            if item.get("allUnder"): continue
            fq = item["fqName"]; simple = item.get("alias") or fq.split(".")[-1]
            if simple not in references and simple not in IMPLICIT_IMPORTS and not re.fullmatch(r"component\d+", simple):
                line = next((i for i,l in enumerate(content.splitlines(),1) if l.lstrip().startswith("import ") and fq in l), 0)
                unused.append((path,line,simple))
    print(f"## Imports sin referencias detectables ({len(unused)})")
    for path,line,name in unused: print(f"  {path}:{line} {name}")
    if args.apply_imports and args.yes and unused:
        files = {a.root / p for p,_,_ in unused}
        backup = backup_files(a.root, files, "unused-imports")
        by_file = collections.defaultdict(set)
        for p,l,_ in unused: by_file[p].add(l)
        for rel, lines in by_file.items():
            p=a.root/rel; original=p.read_bytes().decode("utf-8"); kept=[line for i,line in enumerate(original.splitlines(keepends=True),1) if i not in lines]
            p.write_bytes("".join(kept).encode("utf-8"))
        print(f"Imports eliminados. Copia de seguridad: {backup}")
    elif not args.apply_imports or not args.yes: print("(informe solamente; para borrar imports: --apply-imports --yes)")

def cmd_prune(a: Analyzer, args: argparse.Namespace) -> None:
    selected = a.filtered(args.paths)
    candidates = [n for n in selected if n.status == "muerto" and (n.parent_id not in a.by_id or a.by_id[n.parent_id].status != "muerto")]
    safe, skipped = [], []
    for n in candidates:
        reasons=[]
        if n.visibility != "private": reasons.append("no es private")
        if n.annotations: reasons.append("tiene anotaciones que pueden activar generación/reflexión")
        if n.modifiers & {"expect","actual","external","override"}: reasons.append("modificador con contrato externo")
        if n.kind in {"constructor"}: reasons.append("constructor: eliminación no automatizada")
        if n.file.endswith(".kt") and n.file not in a.raw: reasons.append("archivo no analizado")
        if any(n.id in other.targets and other.status != "muerto" for other in a.nodes): reasons.append("tiene referencias desde código vivo o solo-pruebas")
        if reasons: skipped.append((n,reasons))
        else: safe.append(n)
    print(f"## Plan de prune: {len(safe)} candidatos privados; {len(skipped)} conservados")
    for n in safe: print(f"  CANDIDATO {n.label()} [{n.signature}]")
    for n,why in skipped: print(f"  SE CONSERVA {n.label()}: {', '.join(why)}")
    if a.file_errors:
        print("ABORTADO: hay errores sintácticos PSI. Corrige los archivos antes de aplicar."); return
    if not args.apply:
        print("(simulación; para aplicar: prune --apply --yes [RUTA...])"); return
    if not args.yes:
        print("No se ha modificado nada: --apply exige confirmación explícita --yes."); return
    if not safe:
        print("No hay candidatos seguros para eliminar."); return
    # Never delete enclosing declarations and their children twice.
    ranges=collections.defaultdict(list)
    for n in safe: ranges[n.file].append((n.start,n.end,n))
    files={a.root/f for f in ranges}
    backup=backup_files(a.root,files,"prune")
    for rel, items in ranges.items():
        path=a.root/rel; text=path.read_bytes().decode("utf-8");
        for start,end,n in sorted(items,key=lambda x:x[0],reverse=True):
            # PSI ranges are exact; preserve surrounding whitespace/comments.
            text=text[:start]+text[end:]
        path.write_bytes(text.encode("utf-8"))
    print(f"Eliminadas {len(safe)} declaraciones en {len(files)} archivos. Backup: {backup}")
    print("IMPORTANTE: ejecuta ./gradlew test y ./gradlew assembleDebug; el script no afirma que el proyecto compile.")

def _kt_files(root: pathlib.Path, scope: str) -> list[pathlib.Path]:
    return sorted((root / scope).rglob("*.kt"))

def cmd_dups(root: pathlib.Path, args: argparse.Namespace) -> None:
    """Bloques de >= --min líneas idénticas (sin comentarios, imports ni espacios) en 2+ sitios; ahorro teórico si se extrajeran a una función."""
    trivial = re.compile(r"^(\}|\)|\{|else \{|\}\)|\},?|\)\)|\]|return|\)\.?|\.build\(\))$")
    files: dict[str, list[tuple[int, str]]] = {}
    for p in _kt_files(root, args.scope):
        rows = []
        for n, l in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
            t = l.strip()
            if not t or t.startswith(("import ", "package ", "//", "/*", "*", "@file")) or trivial.match(t): continue
            rows.append((n, re.sub(r"\s+", " ", t)))
        files[p.relative_to(root).as_posix()] = rows
    idx = collections.defaultdict(list)
    for f, rows in files.items():
        for i in range(len(rows) - args.min + 1): idx[tuple(r[1] for r in rows[i:i + args.min])].append((f, i))
    seen: set[tuple[str, int]] = set(); blocks = []
    for occ in idx.values():
        if len(occ) < 2 or all(o in seen for o in occ): continue
        ln = args.min
        while all(i + ln < len(files[f]) for f, i in occ) and len({files[f][i + ln][1] for f, i in occ}) == 1: ln += 1
        for f, i in occ: seen.update((f, i + k) for k in range(ln))
        blocks.append((ln * (len(occ) - 1), ln, occ))
    blocks.sort(key=lambda b: b[0], reverse=True)
    print(f"## {len(blocks)} bloques duplicados (min {args.min} líneas); ahorro teórico {sum(b[0] for b in blocks)} líneas")
    for save, ln, occ in blocks[:args.top]:
        print(f"\n-{save} líneas · {ln} líneas × {len(occ)} sitios")
        for f, i in occ: print(f"   {f}:{files[f][i][0]}")

def cmd_big(root: pathlib.Path, args: argparse.Namespace) -> None:
    """Funciones más largas (heurística por llaves: no entiende cadenas con llaves)."""
    fun = re.compile(r"^\s*(?:@\w+\s+)*(?:(?:private|internal|public|protected|override|open|suspend|inline|operator|abstract)\s+)*fun\s+(?:<[^>]+>\s*)?([\w.]+)")
    out = []
    for p in _kt_files(root, args.scope):
        L = p.read_text(encoding="utf-8").splitlines()
        for i, l in enumerate(L):
            m = fun.match(l)
            if not m: continue
            depth, opened, j = 0, False, i
            while j < len(L):
                depth += L[j].count("{") - L[j].count("}"); opened = opened or "{" in L[j]
                if (opened and depth <= 0) or (not opened and j > i and not L[j - 1].rstrip().endswith(("=", "(", ",", "&&", "||", "?:"))): break
                j += 1
            out.append((j - i + 1, f"{p.relative_to(root).as_posix()}:{i + 1}", m.group(1)))
    out.sort(reverse=True)
    print("## Funciones más largas")
    for n, where, name in out[:args.top]: print(f"{n:5}  {name:32} {where}")

def build_parser() -> argparse.ArgumentParser:
    p=argparse.ArgumentParser(description="Grafo de referencias Kotlin mediante Kotlin PSI")
    p.add_argument("--root",default=".",help="raíz del proyecto Android (por defecto: directorio actual)")
    sub=p.add_subparsers(dest="command",required=True)
    sub.add_parser("stats")
    d=sub.add_parser("dead");d.add_argument("paths",nargs="*");d.add_argument("--tests",action="store_true")
    for name in ("who","deps"):
        x=sub.add_parser(name);x.add_argument("name")
    h=sub.add_parser("html");h.add_argument("output",nargs="?")
    dot=sub.add_parser("dot");dot.add_argument("name");dot.add_argument("depth",nargs="?",type=int,default=2)
    u=sub.add_parser("unused");u.add_argument("paths",nargs="*");u.add_argument("--apply-imports",action="store_true");u.add_argument("--yes",action="store_true")
    pr=sub.add_parser("prune");pr.add_argument("paths",nargs="*");pr.add_argument("--apply",action="store_true");pr.add_argument("--yes",action="store_true")
    for name in ("dups","big"):
        x=sub.add_parser(name,help="bloques de código repetidos" if name=="dups" else "funciones más largas")
        x.add_argument("--scope",default="app/src/main");x.add_argument("--top",type=int,default=40 if name=="dups" else 25)
        if name=="dups": x.add_argument("--min",type=int,default=6)
    return p

def main() -> int:
    args=build_parser().parse_args()
    try:
        if args.command in {"dups","big"}:  # solo texto: no necesitan el análisis PSI
            sys.stdout.reconfigure(encoding="utf-8")
            {"dups":cmd_dups,"big":cmd_big}[args.command](pathlib.Path(args.root),args)
            return 0
        analyzer=Analyzer(pathlib.Path(args.root))
        if args.command=="stats":cmd_stats(analyzer,args)
        elif args.command=="dead":cmd_dead(analyzer,args)
        elif args.command in {"who","deps"}:cmd_lookup(analyzer,args)
        elif args.command=="dot":cmd_dot(analyzer,args)
        elif args.command=="html":cmd_html(analyzer,args)
        elif args.command=="unused":cmd_unused(analyzer,args)
        elif args.command=="prune":cmd_prune(analyzer,args)
        return 0
    except Exception as exc:
        print(f"ERROR: {exc}",file=sys.stderr);return 2

if __name__=="__main__": raise SystemExit(main())
