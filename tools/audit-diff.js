#!/usr/bin/env node
/**
 * Prepara lo que lee el auditor independiente: el diff completo del cambio (lo modificado respecto a HEAD **más los ficheros nuevos**, que `git diff` solo
 * no incluye) y, si se pasa la auditoría anterior, el delta entre las dos (lo que cambió desde que se auditó).
 *
 * Uso (desde la raíz del repo, justo después de `node tools/ship.js check …`, que da el SHA):
 *     node tools/audit-diff.js <SHA>                         -> build/audit-<8 primeras del SHA>.diff
 *     node tools/audit-diff.js <SHA> --prev <SHA anterior>   -> además build/audit-delta-<ant>-<nuevo>.txt (solo las líneas +/- que difieren)
 * Los nuevos son todos los no versionados ni ignorados (los mismos que `ship.js check --add-new`).
 */
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const sha = process.argv[2];
if (!sha || sha.startsWith('--') || !/^[0-9a-f]{8,64}$/.test(sha)) {
  console.error('uso: node tools/audit-diff.js <SHA del check> [--prev <SHA anterior>]');
  process.exit(1);
}
const prevIdx = process.argv.indexOf('--prev');
const prev = prevIdx >= 0 ? process.argv[prevIdx + 1] : null;
const git = (...args) => spawnSync('git', args, { cwd: ROOT, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });

const short = (s) => s.slice(0, 8);
const out = path.join(ROOT, 'build', `audit-${short(sha)}.diff`);
fs.mkdirSync(path.dirname(out), { recursive: true });
let text = git('diff', 'HEAD').stdout;
const untracked = git('ls-files', '--others', '--exclude-standard').stdout.split('\n').filter(Boolean);
for (const f of untracked) text += git('diff', '--no-index', '--', process.platform === 'win32' ? 'NUL' : '/dev/null', f).stdout;
fs.writeFileSync(out, text);
console.log(`[audit-diff] ${path.relative(ROOT, out)}: ${text.split('\n').length} líneas, ${untracked.length} ficheros nuevos`);

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
