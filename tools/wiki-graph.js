#!/usr/bin/env node
/**
 * Grafo de la documentación de Kaizen (solo lectura sobre la documentación).
 *
 * Lee las notas .md de docs/wiki y docs/CODEBASE-MAP.md, saca título, tipo y etiquetas
 * del frontmatter, y une las notas por los enlaces markdown que se hacen entre sí.
 * Escribe UN html con el grafo (Cytoscape.js desde CDN: hace falta internet al abrirlo).
 *
 * Uso:  node tools/wiki-graph.js [--root docs] [--out build/wiki-graph.html]
 *
 * No modifica ninguna nota. Sin dependencias (solo Node).
 */
const fs = require('fs');
const path = require('path');

const REPO = path.resolve(__dirname, '..');
const arg = (name, def) => {
  const i = process.argv.indexOf(name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : def;
};
const ROOT = path.resolve(REPO, arg('--root', 'docs'));
const OUT = path.resolve(REPO, arg('--out', 'build/wiki-graph.html'));

const NL = String.fromCharCode(10);
const BACKSLASH = String.fromCharCode(92);
const rel = (p) => path.relative(REPO, p).split(path.sep).join('/');

function walk(dir, acc) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, acc);
    else if (e.name.endsWith('.md')) acc.push(p);
  }
  return acc;
}

// Frontmatter mínimo: title, type y tags (lista con guiones o en línea [a, b]).
function parseFront(text) {
  const out = { title: '', type: '', tags: [] };
  const lines = text.split(NL).map((l) => l.replace(String.fromCharCode(13), ''));
  if (lines[0] !== '---') return { front: out, body: text };
  const end = lines.indexOf('---', 1);
  if (end < 0) return { front: out, body: text };
  let inTags = false;
  for (const l of lines.slice(1, end)) {
    const t = l.trim();
    if (inTags && t.startsWith('- ')) { out.tags.push(t.slice(2).trim()); continue; }
    inTags = false;
    const k = t.indexOf(':');
    if (k < 0) continue;
    const key = t.slice(0, k).trim();
    const val = t.slice(k + 1).trim();
    if (key === 'title') out.title = val.replace(/^["']|["']$/g, '');
    else if (key === 'type') out.type = val;
    else if (key === 'tags') {
      if (val.startsWith('[')) out.tags = val.slice(1, -1).split(',').map((x) => x.trim()).filter(Boolean);
      else inTags = true;
    }
  }
  return { front: out, body: lines.slice(end + 1).join(NL) };
}

// Destinos de los enlaces markdown "](destino)": solo los que acaban en .md.
function mdLinks(body) {
  const found = [];
  let i = 0;
  while ((i = body.indexOf('](', i)) >= 0) {
    const j = body.indexOf(')', i + 2);
    if (j < 0) break;
    let target = body.slice(i + 2, j).trim();
    i = j + 1;
    const h = target.indexOf('#');
    if (h >= 0) target = target.slice(0, h);
    if (target.endsWith('.md') && !target.includes('://')) found.push(target);
  }
  return found;
}

function firstHeading(body) {
  for (const l of body.split(NL)) if (l.startsWith('# ')) return l.slice(2).trim();
  return '';
}

if (!fs.existsSync(ROOT)) { console.error('No existe ' + ROOT); process.exit(1); }
const files = walk(ROOT, []).sort();
const byPath = new Map();
const nodes = [];
for (const f of files) {
  const { front, body } = parseFront(fs.readFileSync(f, 'utf8'));
  const id = rel(f);
  const node = {
    id,
    label: front.title || firstHeading(body) || path.basename(f, '.md'),
    type: front.type || 'sin-type',
    tags: front.tags,
    words: body.split(' ').filter(Boolean).length,
    links: mdLinks(body),
  };
  byPath.set(id, node);
  nodes.push(node);
}

const edges = [];
const broken = [];
for (const n of nodes) {
  const seen = new Set();
  for (const t of n.links) {
    const dest = rel(path.resolve(path.dirname(path.join(REPO, n.id)), t));
    if (!byPath.has(dest)) {
      // Existe pero fuera de la carpeta leída (p. ej. tools/README.md): no es un enlace roto.
      if (!fs.existsSync(path.join(REPO, dest))) broken.push(n.id + ' -> ' + t);
      continue;
    }
    if (dest === n.id || seen.has(dest)) continue;
    seen.add(dest);
    edges.push({ source: n.id, target: dest });
  }
}

const data = {
  generated: new Date().toISOString().slice(0, 10),
  nodes: nodes.map(({ links, ...n }) => n),
  edges,
  broken,
};

const html = `<!doctype html>
<html lang="es"><head><meta charset="utf-8"><title>Grafo de la documentación de Kaizen</title>
<style>
body{margin:0;font:14px system-ui,sans-serif;display:flex;height:100vh;color:#222}
#cy{flex:1}
#side{width:300px;padding:12px;border-left:1px solid #ddd;overflow:auto}
input,select{width:100%;margin-bottom:8px;padding:4px;box-sizing:border-box}
.tag{display:inline-block;background:#eee;border-radius:8px;padding:0 6px;margin:1px;font-size:12px}
h3{margin:.2em 0}
</style></head><body>
<div id="cy"></div>
<div id="side">
<h3>Documentación Kaizen</h3>
<div id="meta"></div>
<input id="q" placeholder="Buscar por título o etiqueta">
<select id="t"><option value="">Todos los tipos</option></select>
<div id="info">Pulsa un nodo para ver su detalle.</div>
<div id="leg"></div>
</div>
<script src="https://unpkg.com/cytoscape@3.30.2/dist/cytoscape.min.js" integrity="sha384-IWROdLKRsN1UuJywMlWl7/blXQ8GEooN2n7dzTxfEPd7ybYIKCUJ2Ol/1Gpf3YV4" crossorigin="anonymous"></script>
<script>
const D = ${JSON.stringify(data).split('<').join(BACKSLASH + 'u003c')};
const esc = s => String(s).split('&').join('&amp;').split('<').join('&lt;').split('>').join('&gt;').split('"').join('&quot;');
const palette = ['#4e79a7','#f28e2b','#59a14f','#e15759','#b07aa1','#76b7b2','#edc948','#9c755f'];
const types = [...new Set(D.nodes.map(n => n.type))].sort();
const color = t => palette[types.indexOf(t) % palette.length];
document.getElementById('meta').textContent = D.nodes.length + ' notas, ' + D.edges.length + ' enlaces, ' + D.broken.length + ' enlaces rotos. Generado ' + D.generated + '.';
const sel = document.getElementById('t');
types.forEach(t => { const o = document.createElement('option'); o.value = t; o.textContent = t; sel.appendChild(o); });
document.getElementById('leg').innerHTML = types.map(t => '<div><span style="color:' + color(t) + '">&#9679;</span> ' + esc(t) + '</div>').join('');
const cy = cytoscape({
  container: document.getElementById('cy'),
  elements: [
    ...D.nodes.map(n => ({ data: { id: n.id, label: n.label, type: n.type, size: 18 + Math.min(40, Math.sqrt(n.words)) } })),
    ...D.edges.map((e, i) => ({ data: { id: 'e' + i, source: e.source, target: e.target } })),
  ],
  style: [
    { selector: 'node', style: { label: 'data(label)', 'background-color': e => color(e.data('type')), width: 'data(size)', height: 'data(size)', 'font-size': 10, 'text-wrap': 'wrap', 'text-max-width': 110 } },
    { selector: 'edge', style: { width: 1, 'line-color': '#bbb', 'target-arrow-color': '#bbb', 'target-arrow-shape': 'triangle', 'curve-style': 'bezier' } },
    { selector: '.dim', style: { opacity: 0.12 } },
  ],
  layout: { name: 'cose', animate: false, nodeRepulsion: 9000 },
});
const byId = Object.fromEntries(D.nodes.map(n => [n.id, n]));
cy.on('tap', 'node', ev => {
  const n = byId[ev.target.id()];
  const out = D.edges.filter(e => e.source === n.id).map(e => byId[e.target].label);
  const inn = D.edges.filter(e => e.target === n.id).map(e => byId[e.source].label);
  document.getElementById('info').innerHTML = '<h3>' + esc(n.label) + '</h3><div>' + esc(n.id) + '</div><div>tipo: ' + esc(n.type) + ' · ' + n.words + ' palabras</div><div>' + n.tags.map(t => '<span class="tag">' + esc(t) + '</span>').join('') + '</div><p><b>Enlaza a:</b> ' + (out.map(esc).join(', ') || 'nada') + '</p><p><b>Enlazada desde:</b> ' + (inn.map(esc).join(', ') || 'nadie') + '</p>';
});
function filtrar() {
  const q = document.getElementById('q').value.toLowerCase(), t = sel.value;
  cy.nodes().forEach(x => {
    const n = byId[x.id()];
    const ok = (!t || n.type === t) && (!q || (n.label + ' ' + n.tags.join(' ')).toLowerCase().includes(q));
    x.toggleClass('dim', !ok);
  });
}
document.getElementById('q').oninput = filtrar;
sel.onchange = filtrar;
</script></body></html>
`;

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, html);
console.log(nodes.length + ' notas, ' + edges.length + ' enlaces, ' + broken.length + ' rotos -> ' + rel(OUT));
if (broken.length) console.log('Enlaces rotos:' + NL + broken.map((b) => '  ' + b).join(NL));
