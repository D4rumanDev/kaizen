#!/usr/bin/env node
/**
 * Prepara lo que lee el auditor independiente: el diff completo del cambio (lo modificado respecto a HEAD **más los ficheros nuevos**, que `git diff` solo
 * no incluye) y, si se pasa la auditoría anterior, el delta entre las dos (lo que cambió desde que se auditó).
 *
 * Uso (desde la raíz del repo, justo después de `node tools/ship.js check …`, que da el SHA):
 *     node tools/audit-diff.js <SHA>                         -> build/audit-<8 primeras del SHA>.diff
 *     node tools/audit-diff.js <SHA> --prev <SHA anterior>   -> además build/audit-delta-<ant>-<nuevo>.txt (solo las líneas +/- que difieren)
 *     node tools/audit-diff.js <SHA> --exclude ruta ...      -> sin esas rutas (fichero o carpeta), como en `ship.js check`
 * Si build/ship-check.json es de ese SHA, el diff lleva **exactamente** los ficheros que comprobó `check` (los mismos que irán al commit). Si no, lleva lo
 * modificado y todos los nuevos no versionados ni ignorados, menos lo de --exclude.
 */
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const sha = process.argv[2];
if (!sha || sha.startsWith('--') || !/^[0-9a-f]{8,64}$/.test(sha)) {
  console.error('uso: node tools/audit-diff.js <SHA del check> [--prev <SHA anterior>] [--exclude ruta]...');
  process.exit(1);
}
const prevIdx = process.argv.indexOf('--prev');
const prev = prevIdx >= 0 ? process.argv[prevIdx + 1] : null;
const exclude = process.argv.reduce((out, v, i, all) => (v === '--exclude' && all[i + 1] ? [...out, all[i + 1].replace(/\\/g, '/')] : out), []);
const excluded = (f) => exclude.some((x) => f === x || f.startsWith(x.endsWith('/') ? x : `${x}/`));
// --literal-pathspecs: un nombre de fichero con *, ? o [ no se toma por un patrón (como en ship.js).
const git = (...args) => spawnSync('git', ['--literal-pathspecs', ...args], { cwd: ROOT, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });

/** Los ficheros del cambio: los de `check` si es este SHA; si no, lo modificado y lo nuevo, menos lo excluido. */
function changeFiles() {
  const state = path.join(ROOT, 'build', 'ship-check.json');
  if (fs.existsSync(state)) {
    const checked = JSON.parse(fs.readFileSync(state, 'utf8'));
    if (typeof checked.sha === 'string' && checked.sha.startsWith(sha) && Array.isArray(checked.files)) return { files: checked.files.filter((f) => !excluded(f)), from: 'ship-check.json' };
  }
  const tracked = git('diff', '--name-only', '--no-renames', 'HEAD').stdout.split('\n').filter(Boolean);
  const untracked = git('ls-files', '--others', '--exclude-standard').stdout.split('\n').filter(Boolean);
  return { files: [...new Set([...tracked, ...untracked])].filter((f) => !excluded(f)).sort(), from: 'git (sin ship-check.json de este SHA)' };
}

const short = (s) => s.slice(0, 8);
const out = path.join(ROOT, 'build', `audit-${short(sha)}.diff`);
fs.mkdirSync(path.dirname(out), { recursive: true });
const { files, from } = changeFiles();
const isNew = (f) => git('cat-file', '-e', `HEAD:${f}`).status !== 0;
const added = files.filter(isNew);
const changed = files.filter((f) => !added.includes(f));
let text = changed.length ? git('diff', 'HEAD', '--', ...changed).stdout : '';
for (const f of added) if (fs.existsSync(path.join(ROOT, f))) text += git('diff', '--no-index', '--', process.platform === 'win32' ? 'NUL' : '/dev/null', f).stdout;
fs.writeFileSync(out, text);
console.log(`[audit-diff] ${path.relative(ROOT, out)}: ${text.split('\n').length} líneas, ${files.length} ficheros (${added.length} nuevos), según ${from}${exclude.length ? `; excluidos: ${exclude.join(', ')}` : ''}`);

if (prev) {
  const before = path.join(ROOT, 'build', `audit-${short(prev)}.diff`);
  if (!fs.existsSync(before)) { console.error(`[audit-diff] no existe ${path.relative(ROOT, before)}`); process.exit(1); }
  const lines = (t) => t.split('\n').filter((l) => /^[+-]/.test(l) && !/^(\+\+\+|---) /.test(l));
  const a = lines(fs.readFileSync(before, 'utf8'));
  const b = lines(text);
  const countOf = (arr) => arr.reduce((m, l) => m.set(l, (m.get(l) || 0) + 1), new Map());
  const ca = countOf(a), cb = countOf(b);
  const delta = [];
  for (const [l, n] of cb) if (n > (ca.get(l) || 0)) delta.push(`> ${l}`);
  for (const [l, n] of ca) if (n > (cb.get(l) || 0)) delta.push(`< ${l}`);
  const file = path.join(ROOT, 'build', `audit-delta-${short(prev)}-${short(sha)}.txt`);
  fs.writeFileSync(file, delta.join('\n') + '\n');
  console.log(`[audit-diff] ${path.relative(ROOT, file)}: ${delta.length} líneas distintas (">" nuevas, "<" que ya no están)`);
}
